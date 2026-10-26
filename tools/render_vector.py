"""
把 ic_launcher.xml 精确渲染为 PNG。

相比之前的版本，这里用更完整的 SVG 路径解析（正确处理 A 圆弧指令），
以便像素级验证构图：背景渐变 / 手机框 / 扫帚 / 星星 是否都在正确位置。
"""
import re
import math
import sys
from PIL import Image, ImageDraw


def parse_path(d):
    """把 pathData 解析为 (cmd, [numbers]) 序列"""
    pattern = r'([MLAQmlaqzZ])|(-?\d*\.?\d+)'
    tokens = re.findall(pattern, d)
    out = []
    cur = None
    for cmd, num in tokens:
        if cmd:
            cur = cmd
            out.append((cmd, []))
        elif cur is not None:
            out[-1][1].append(float(num))
    return out


def arc_to_points(x0, y0, rx, ry, large, sweep, x1, y1, n=24):
    """
    把 SVG 圆弧转成折线点。
    这里用标准椭圆参数化（本项目所有圆弧都是 90 度圆角，够精确）。
    """
    if rx == 0 or ry == 0:
        return [(x1, y1)]

    # 端点参数化
    dx2 = (x0 - x1) / 2.0
    dy2 = (y0 - y1) / 2.0

    # 旋转为 0（本项目没有旋转圆弧）
    x1p, y1p = dx2, dy2

    # 修正半径
    lam = (x1p ** 2) / (rx ** 2) + (y1p ** 2) / (ry ** 2)
    if lam > 1:
        s = math.sqrt(lam)
        rx *= s
        ry *= s

    sign = -1 if large == sweep else 1
    num = rx * rx * ry * ry - rx * rx * y1p * y1p - ry * ry * x1p * x1p
    den = rx * rx * y1p * y1p + ry * ry * x1p * x1p
    co = sign * math.sqrt(max(0.0, num / den)) if den != 0 else 0

    cxp = co * rx * y1p / ry
    cyp = -co * ry * x1p / rx
    cx = cxp + (x0 + x1) / 2.0
    cy = cyp + (y0 + y1) / 2.0

    def angle(ux, uy, vx, vy):
        dot = ux * vx + uy * vy
        n1 = math.hypot(ux, uy)
        n2 = math.hypot(vx, vy)
        if n1 == 0 or n2 == 0:
            return 0.0
        a = math.acos(max(-1.0, min(1.0, dot / (n1 * n2))))
        return -a if (ux * vy - uy * vx) < 0 else a

    theta1 = angle(1, 0, (x1p - cxp) / rx, (y1p - cyp) / ry)
    dtheta = angle((x1p - cxp) / rx, (y1p - cyp) / ry,
                   (-x1p - cxp) / rx, (-y1p - cyp) / ry)

    if not sweep and dtheta > 0:
        dtheta -= 2 * math.pi
    elif sweep and dtheta < 0:
        dtheta += 2 * math.pi

    pts = []
    for i in range(1, n + 1):
        t = theta1 + dtheta * i / n
        pts.append((cx + rx * math.cos(t), cy + ry * math.sin(t)))
    return pts


def path_to_points(d):
    """返回 (多边形点列, 是否闭合)"""
    cmds = parse_path(d)
    pts = []
    x = y = 0.0
    closed = False
    for cmd, nums in cmds:
        c = cmd.upper()
        rel = cmd.islower()
        if c == 'M':
            for i in range(0, len(nums) - 1, 2):
                px, py = nums[i], nums[i + 1]
                x, y = (x + px, y + py) if rel else (px, py)
                pts.append((x, y))
        elif c == 'L':
            for i in range(0, len(nums) - 1, 2):
                px, py = nums[i], nums[i + 1]
                x, y = (x + px, y + py) if rel else (px, py)
                pts.append((x, y))
        elif c == 'A':
            for i in range(0, len(nums) - 6, 7):
                rx, ry, rot, large, sweep, px, py = nums[i:i + 7]
                ex, ey = (x + px, y + py) if rel else (px, py)
                pts.extend(arc_to_points(x, y, rx, ry, int(large), int(sweep), ex, ey))
                x, y = ex, ey
        elif c == 'Q':
            for i in range(0, len(nums) - 3, 4):
                qx, qy, px, py = nums[i:i + 4]
                if rel:
                    qx, qy, px, py = x + qx, y + qy, x + px, y + py
                for k in range(1, 13):
                    t = k / 12
                    bx = (1 - t) ** 2 * x + 2 * (1 - t) * t * qx + t ** 2 * px
                    by = (1 - t) ** 2 * y + 2 * (1 - t) * t * qy + t ** 2 * py
                    pts.append((bx, by))
                x, y = px, py
        elif c == 'Z':
            closed = True
    return pts, closed


def hex_rgba(s):
    """
    解析 Android 颜色。

    注意：Android 的 8 位颜色格式是 #AARRGGBB（alpha 在前），
    不是 CSS 的 #RRGGBBAA。这里必须按 AARRGGBB 解析。
    """
    s = s.lstrip('#')
    if len(s) == 8:
        a = int(s[0:2], 16)
        r = int(s[2:4], 16)
        g = int(s[4:6], 16)
        b = int(s[6:8], 16)
        return (r, g, b, a)
    if len(s) == 6:
        return (int(s[0:2], 16), int(s[2:4], 16), int(s[4:6], 16), 255)
    return (0, 0, 0, 255)


def render(svg_file, out_png, size=512):
    with open(svg_file, encoding='utf-8') as f:
        svg = f.read()

    scale = size / 108.0
    base = Image.new('RGBA', (size, size), (0, 0, 0, 0))

    # 按 <path 开头切分，每块直到下一个 <path 或 </vector>
    # 不能简单用 '/>' 截断，因为内部的 <gradient .../> 也以 /> 结尾
    starts = [m.start() for m in re.finditer(r'<path\b', svg)]
    path_blocks = []
    for i, s in enumerate(starts):
        e = starts[i + 1] if i + 1 < len(starts) else len(svg)
        path_blocks.append((s, svg[s:e]))
    print(f"path 数量: {len(path_blocks)}")

    # 解析 <group> 旋转区间，用于给其中的 path 应用变换
    groups = []
    for m in re.finditer(r'<group\b(.*?)>(.*?)</group>', svg, re.S):
        attrs, body = m.group(1), m.group(2)
        rot = re.search(r'android:rotation="(-?[\d.]+)"', attrs)
        pvx = re.search(r'android:pivotX="([\d.]+)"', attrs)
        pvy = re.search(r'android:pivotY="([\d.]+)"', attrs)
        if rot:
            groups.append({
                'start': m.start(2),
                'end': m.end(2),
                'rot': float(rot.group(1)),
                'px': float(pvx.group(1)) if pvx else 0.0,
                'py': float(pvy.group(1)) if pvy else 0.0,
            })
    if groups:
        print(f"group 旋转: {[(g['rot'], g['px'], g['py']) for g in groups]}")

    def transform(pts, offset):
        """若该 path 位于某个 <group> 内，应用旋转"""
        for g in groups:
            if g['start'] <= offset < g['end']:
                a = math.radians(g['rot'])
                ca, sa = math.cos(a), math.sin(a)
                cx, cy = g['px'], g['py']
                out = []
                for x, y in pts:
                    dx, dy = x - cx, y - cy
                    out.append((cx + dx * ca - dy * sa, cy + dx * sa + dy * ca))
                return out
        return pts

    for idx, (offset, block) in enumerate(path_blocks):
        d_m = re.search(r'android:pathData="([^"]+)"', block)
        if not d_m:
            continue
        pts, closed = path_to_points(d_m.group(1))
        if len(pts) < 2:
            continue
        pts = transform(pts, offset)
        pts = [(px * scale, py * scale) for px, py in pts]

        # 渐变填充
        grad = re.search(r'<gradient(.*?)</gradient>', block, re.S)
        fill = re.search(r'android:fillColor="(#[0-9A-Fa-f]{6,8})"', block)
        stroke = re.search(r'android:strokeColor="(#[0-9A-Fa-f]{6,8})"', block)
        swidth = re.search(r'android:strokeWidth="([\d.]+)"', block)
        scl = re.search(r'android:strokeLineCap="(\w+)"', block)

        layer = Image.new('RGBA', (size, size), (0, 0, 0, 0))
        ld = ImageDraw.Draw(layer)

        if grad:
            g = grad.group(1)
            stops = [(float(o), hex_rgba(c)) for o, c in
                     re.findall(r'android:offset="([\d.]+)"\s+android:color="(#[0-9A-Fa-f]{6,8})"', g)]
            sx = float(re.search(r'android:startX="([\d.]+)"', g).group(1)) * scale
            sy = float(re.search(r'android:startY="([\d.]+)"', g).group(1)) * scale
            ex = float(re.search(r'android:endX="([\d.]+)"', g).group(1)) * scale
            ey = float(re.search(r'android:endY="([\d.]+)"', g).group(1)) * scale

            # 用线性渐变逐像素填充
            mask = Image.new('L', (size, size), 0)
            ImageDraw.Draw(mask).polygon(pts, fill=255)
            gimg = Image.new('RGBA', (size, size))
            gp = gimg.load()
            vx, vy = ex - sx, ey - sy
            vlen2 = vx * vx + vy * vy or 1.0
            for yy in range(size):
                for xx in range(size):
                    t = ((xx - sx) * vx + (yy - sy) * vy) / vlen2
                    t = max(0.0, min(1.0, t))
                    col = stops[-1][1]
                    for k in range(len(stops) - 1):
                        o1, c1 = stops[k]
                        o2, c2 = stops[k + 1]
                        if o1 <= t <= o2:
                            tt = (t - o1) / max(1e-9, o2 - o1)
                            col = tuple(int(c1[j] + (c2[j] - c1[j]) * tt) for j in range(4))
                            break
                    gp[xx, yy] = col
            layer.paste(gimg, (0, 0), mask)
            base = Image.alpha_composite(base, layer)
            continue

        if fill:
            col = hex_rgba(fill.group(1))
            if col[3] > 0:
                ld.polygon(pts, fill=col)
                base = Image.alpha_composite(base, layer)
                layer = Image.new('RGBA', (size, size), (0, 0, 0, 0))
                ld = ImageDraw.Draw(layer)

        if stroke:
            col = hex_rgba(stroke.group(1))
            w = max(1, int(round(float(swidth.group(1)) * scale))) if swidth else 2
            line = pts + ([pts[0]] if closed else [])
            ld.line(line, fill=col, width=w, joint='curve')
            # 线帽
            if scl and scl.group(1) == 'round':
                for p in (pts[0], pts[-1]):
                    ld.ellipse([p[0] - w / 2, p[1] - w / 2, p[0] + w / 2, p[1] + w / 2], fill=col)
            base = Image.alpha_composite(base, layer)

    base.save(out_png)
    print(f"已渲染: {out_png}")
    return base


def verify(png):
    img = Image.open(png).convert('RGBA')
    w, h = img.size
    px = img.load()
    print(f"\n=== 像素验证 ({w}x{h}) ===")

    def at(fx, fy):
        return px[int(w * fx), int(h * fy)]

    checks = [
        ("背景左上（应为青蓝）",   0.10, 0.10),
        ("背景右下（应为深蓝）",   0.90, 0.90),
        ("手机框上边（应为白）",   0.50, 0.235),
        ("手机框左边（应为白）",   0.255, 0.50),
        ("扫帚手柄（应为白）",     0.50, 0.42),
        ("扫帚刷毛（应为白/浅）",  0.50, 0.66),
        ("右上大星（应为白）",     0.852, 0.185),
        ("右上小星（应为白）",     0.676, 0.093),
    ]
    for name, fx, fy in checks:
        r, g, b, a = at(fx, fy)
        print(f"  {name:24} ({fx:.3f},{fy:.3f}) = RGBA({r},{g},{b},{a})")

    # 统计
    from collections import Counter
    c = Counter()
    for y in range(0, h, 2):
        for x in range(0, w, 2):
            r, g, b, a = px[x, y]
            if a > 30:
                c[(r // 24 * 24, g // 24 * 24, b // 24 * 24)] += 1
    print("\n主色 Top6:")
    for col, n in c.most_common(6):
        print(f"  RGB{col}: {n}")


if __name__ == '__main__':
    src = sys.argv[1] if len(sys.argv) > 1 else 'app/src/main/res/drawable/ic_launcher.xml'
    dst = sys.argv[2] if len(sys.argv) > 2 else 'icon_preview.png'
    render(src, dst)
    verify(dst)

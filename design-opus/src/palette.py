"""VR palette (the settings panel on the controller), danmaku, subtitles and screen manipulation.

Two kinds of drawing live here:
- palette pages at true texture size (1024 x 768 texels = 20 x 15 cm), scaled down for display;
- head-view scenes on the usual VR canvas (80 x 50 degrees, 1 degree = 20 px, gaze centre at 800, 500),
  plus diagrams: top-down float, side-view mount, trigger timeline, curved screen.
Every size that matters is written in cm or degrees in the annotations; see ux-spec.md sections 6.3-6.12."""
from lib import ic, scene, page, ROOM
from vr import VR_CSS, screen, subt, bar, ray

EXTRA_ICONS = {
    'pin': '<path d="M9 3.6h6l-.9 5.6 3.1 3.2H6.8l3.1-3.2z"/><path d="M12 12.4v8"/>',
    'dist': '<path d="M12 3.5v17M8.2 7.3 12 3.5l3.8 3.8M8.2 16.7 12 20.5l3.8-3.8"/>',
    'size': '<path d="M4.5 9.5v-5h5M19.5 14.5v5h-5M4.5 4.5l6 6M19.5 19.5l-6-6"/>',
    'pinch': '<path d="M7 13.5c0-3 2-5.5 5-5.5"/><circle cx="12" cy="8" r="2.2"/><path d="M12 10.2v8.3M8.5 18.5h7"/>',
}


def sprite():
    syms = ''.join(f'<symbol id="i-{k}" viewBox="0 0 24 24">{v}</symbol>' for k, v in EXTRA_ICONS.items())
    return f'<svg width="0" height="0" style="position:absolute" aria-hidden="true">{syms}</svg>'


CSS = VR_CSS + """
.anno.note{bottom:62px}
.anno.st{flex-wrap:wrap;max-width:1560px}
/* ---------- palette, drawn in texture px (1024 x 768 = 20 x 15 cm) ---------- */
.palw{position:absolute;transform-origin:0 0;z-index:8}
.pal{position:absolute;left:0;top:0;width:1024px;height:768px;border-radius:48px;background:rgba(18,19,23,.94);color:var(--text);overflow:hidden}
.pal .tb{position:absolute;top:24px;height:128px;border-radius:64px;display:flex;align-items:center;justify-content:center;
  font-size:56px;font-weight:500;color:var(--text-2);white-space:nowrap}
.pal .tb.sel{background:rgba(255,255,255,.20);color:var(--text);font-weight:600}
.pal .hb{position:absolute;top:24px;left:872px;width:128px;height:128px;border-radius:64px;display:grid;place-items:center;
  background:rgba(255,255,255,.10);color:var(--text)}
.pal .hb.on{background:var(--text);color:#0E0F12}
.pal .hb .ic{width:56px;height:56px;stroke-width:2}
.pal .rw{position:absolute;left:24px;width:976px;height:144px;border-radius:32px}
.pal .rw.hov{background:rgba(255,255,255,.08)}
.pal .lb{position:absolute;left:32px;top:44px;font-size:56px;line-height:56px;font-weight:500;color:var(--text-2);white-space:nowrap}
.pal .rw.hov .lb{color:var(--text)}
.pal .sg{position:absolute;left:224px;top:8px;height:128px;display:flex;gap:8px}
.pal .sg span{height:128px;border-radius:24px;display:flex;align-items:center;justify-content:center;font-size:56px;font-weight:500;
  color:var(--text-2);white-space:nowrap}
.pal .sg span.sel{background:rgba(255,255,255,.20);color:var(--text);font-weight:600}
.pal .sg span.hv{background:rgba(255,255,255,.12);color:var(--text)}
.pal .sg span.x{background:rgba(255,255,255,.20);color:var(--text);text-decoration:line-through;text-decoration-thickness:5px}
.pal .vl{position:absolute;top:8px;height:128px;display:flex;align-items:center;gap:6px;font-size:60px;font-weight:600;white-space:nowrap}
.pal .vl .ic{width:44px;height:44px;color:var(--text-2);stroke-width:2.6}
.pal .sl{position:absolute;top:66px;height:12px;border-radius:6px;background:rgba(255,255,255,.22)}
.pal .sl b{position:absolute;left:0;top:0;bottom:0;border-radius:6px;background:rgba(236,236,238,.72)}
.pal .sl i{position:absolute;top:-12px;width:6px;height:36px;margin-left:-3px;border-radius:3px;background:rgba(255,255,255,.40)}
.pal .sl u{position:absolute;top:-16px;width:44px;height:44px;margin-left:-22px;border-radius:22px;background:var(--text)}
.pal .cp{position:absolute;left:224px;top:8px;width:152px;height:128px;border-radius:24px;display:flex;align-items:center;justify-content:center;
  gap:12px;font-size:56px;font-weight:500;color:var(--text-2);background:rgba(255,255,255,.08)}
.pal .cp.on{background:rgba(255,255,255,.20);color:var(--text);font-weight:600}
.pal .cp.on::before{content:"";width:16px;height:16px;border-radius:8px;background:var(--accent)}
.pal .rs{position:absolute;left:840px;top:8px;width:128px;height:128px;border-radius:64px;display:grid;place-items:center;
  background:rgba(255,255,255,.08);color:var(--text-2)}
.pal .rs .ic{width:52px;height:52px}
.pal .lk{position:absolute;left:32px;right:32px;top:0;height:144px;display:flex;align-items:center;justify-content:space-between;
  font-size:56px;font-weight:500;color:var(--text-2);white-space:nowrap}
.pal .lk .ic{width:52px;height:52px}
.pal .ms{position:absolute;left:32px;top:44px;font-size:56px;line-height:56px;font-weight:500;color:var(--text-3);white-space:nowrap}
.pal .ms.c1{color:var(--text-2)}
.pal .rw.dis .lb,.pal .rw.dis .vl,.pal .rw.dis .sg span,.pal .rw.dis .cp{color:var(--text-3)}
.pal .rw.dis .sg span.sel{background:rgba(255,255,255,.07);color:var(--text-3)}
.pal .rw.dis .sl b{background:rgba(255,255,255,.22)}
.pal .rw.dis .sl u{background:var(--text-3)}
.pal .why{position:absolute;left:224px;top:44px;font-size:56px;line-height:56px;font-weight:500;color:var(--text-3);white-space:nowrap}
/* ---------- danmaku on the head view (1 deg = 20 px; 1.4 deg = 28 px) ---------- */
.dml{position:absolute;left:304px;top:221px;width:992px;height:558px;overflow:hidden;z-index:3;
  -webkit-mask-image:linear-gradient(90deg,transparent 0,#000 30px,#000 962px,transparent 992px);
  mask-image:linear-gradient(90deg,transparent 0,#000 30px,#000 962px,transparent 992px)}
.dmi{position:absolute;font-size:28px;line-height:36px;font-weight:700;white-space:nowrap;color:#F3F3F1;opacity:.8;
  text-shadow:0 0 1px #0E0F12,1px 1px 1px rgba(14,15,18,.92),-1px -1px 1px rgba(14,15,18,.92),1px -1px 1px rgba(14,15,18,.92),
  -1px 1px 1px rgba(14,15,18,.92),0 1px 3px rgba(0,0,0,.5)}
.dmi.fx{transform:translateX(-50%)}
.subt.box span{background:rgba(14,15,18,.64);padding:2px 12px;border-radius:8px;text-shadow:none}
/* danmaku button on the control bar */
.vround.dmb{width:56px;height:56px;background:rgba(255,255,255,.10);font-size:24px;font-weight:700}
.vround.dmb span{color:var(--accent)}
.vround.dmb.on{box-shadow:inset 0 0 0 2px rgba(227,168,87,.75)}
.vround.dmb.off span{color:var(--text-2)}
.vround.dmb.off i{position:absolute;left:12px;top:27px;width:32px;height:3px;border-radius:2px;background:var(--text-2);transform:rotate(-40deg)}
.vround.dmb.hov{background:rgba(255,255,255,.20);transform:scale(1.06)}
/* ---------- annotation helpers (not part of the design) ---------- */
.spec{position:absolute;z-index:30;font:500 17px/1.6 "PingFang SC","Noto Sans SC","Noto Sans CJK SC",sans-serif;color:#e8e8ea;
  background:rgba(18,18,20,.86);border-radius:12px;padding:16px 20px}
.spec h3{font-size:20px;line-height:1.4;font-weight:600;color:#fff;margin:0 0 6px}
.spec h4{font-size:17px;font-weight:600;color:#fff;margin:12px 0 2px}
.spec p{margin-top:4px}
.spec .k{color:#7FD7FF;font:500 15px/1.5 "SF Mono",Menlo,monospace}
.spec table{border-collapse:collapse;margin-top:6px;width:100%}
.spec td,.spec th{padding:4px 10px 4px 0;vertical-align:top;border-top:1px solid rgba(255,255,255,.10);text-align:left}
.spec th{color:#fff;font-weight:600;border-top:0}
.spec td:first-child{color:#a6a9b0;white-space:nowrap}
.spec .num td{font-variant-numeric:tabular-nums}
.spec.sm{font-size:15px;line-height:1.55;padding:12px 18px}
.spec.sm h3{font-size:17px;margin-bottom:4px}
.diag{position:absolute;left:0;top:0;z-index:10}
.diag text{font-family:"PingFang SC","Noto Sans SC","Noto Sans CJK SC",sans-serif}
.bg-plain{position:absolute;inset:0;background:#101114;z-index:1}
.cap{position:absolute;z-index:30;font:500 16px/1.5 "PingFang SC","Noto Sans SC",sans-serif;color:#e8e8ea}
.cap b{color:#fff;font-weight:600}
.tl{position:absolute;z-index:12;height:22px;border-radius:11px}
.tl.trig{background:rgba(242,242,242,.82)}
.tl.stk{background:#8E949E;height:14px;border-radius:7px}
.tl.adj{background:rgba(127,215,255,.28);height:40px;border-radius:8px}
.tlab{position:absolute;z-index:13;font:500 15px/1.4 "PingFang SC","Noto Sans SC",sans-serif;color:#cfd2d8;white-space:nowrap}
.tres{position:absolute;z-index:13;font:600 17px/1.4 "PingFang SC","Noto Sans SC",sans-serif;color:#fff;white-space:nowrap}
.ring2{position:absolute;width:34px;height:34px;margin:-17px 0 0 -17px;border-radius:50%;border:3px solid #fff;
  box-shadow:0 0 0 3px rgba(0,0,0,.4);z-index:21}
"""

# ---------------------------------------------------------------- palette (texture px)
TABS = (('3D', 128), ('屏幕', 144), ('弹幕', 144), ('字幕音轨', 256))
ROW_Y = (168, 312, 456, 600)


def tabs(sel, names=TABS):
    x, out = 24, ''
    for t, w in names:
        out += f'<div class="tb{" sel" if t == sel else ""}" style="left:{x}px;width:{w}px">{t}</div>'
        x += w + 8
    return out


def _row(i, inner, hov=False, dis=False):
    cls = 'rw' + (' hov' if hov else '') + (' dis' if dis else '')
    return f'<div class="{cls}" style="top:{ROW_Y[i]}px">{inner}</div>'


def _reset(on):
    return f'<div class="rs">{ic("restart")}</div>' if on else ''


def r_seg(i, label, opts, sel, hv=None, x=(), reset=False, hov=False, dis=False):
    n = len(opts)
    w = (592 - 8 * (n - 1)) // n
    spans = ''
    for o in opts:
        c = 'x' if o in x else ('sel' if o == sel else ('hv' if o == hv else ''))
        spans += f'<span class="{c}" style="width:{w}px">{o}</span>'
    return _row(i, f'<div class="lb">{label}</div><div class="sg">{spans}</div>' + _reset(reset), hov, dis)


def _slider(x0, x1, frac, ticks=()):
    w = x1 - x0
    t = ''.join(f'<i style="left:{f * 100:.1f}%"></i>' for f in ticks)
    return (f'<div class="sl" style="left:{x0}px;width:{w}px"><b style="width:{frac * 100:.1f}%"></b>{t}'
            f'<u style="left:{frac * 100:.1f}%"></u></div>')


def r_cont(i, label, value, frac, ticks=(), chip=None, reset=False, hov=False, dis=False):
    if chip:
        name, on = chip
        lead = f'<div class="cp{" on" if on else ""}">{name}</div>'
        vx, sx = 392, 600
    else:
        lead, vx, sx = '', 224, 520
    val = (f'{ic("chev-l")}{value}{ic("chev-r")}' if hov else value)
    inner = (f'<div class="lb">{label}</div>{lead}<div class="vl" style="left:{vx}px">{val}</div>'
             + _slider(sx, 816, frac, ticks) + _reset(reset))
    return _row(i, inner, hov, dis)


def r_step(i, label, value, hov=False, dis=False):
    inner = (f'<div class="lb">{label}</div><div class="vl" style="left:224px;width:592px;justify-content:space-between">'
             f'{ic("chev-l")}<span>{value}</span>{ic("chev-r")}</div>')
    return _row(i, inner, hov, dis)


def r_link(i, text, back=False, hov=False):
    inner = (f'<div class="lk"><span>{ic("chev-l")} 返回</span><span></span></div>' if back else
             f'<div class="lk"><span>{text}</span>{ic("chev-r")}</div>')
    return _row(i, inner, hov)


def r_msg(i, text, strong=False):
    return _row(i, f'<div class="ms{" c1" if strong else ""}">{text}</div>')


def r_why(i, label, why):
    return _row(i, f'<div class="lb">{label}</div><div class="why">{why}</div>', dis=True)


def pal(sel, rows, x, y, s=.75, hb='pin', tabset=TABS, extra='', alpha=1, tf=''):
    head = tabs(sel, tabset)
    if hb == 'pin':
        head += f'<div class="hb">{ic("pin")}</div>'
    elif hb == 'pinned':
        head += f'<div class="hb on">{ic("pin")}</div>'
    elif hb == 'close':
        head += f'<div class="hb">{ic("close")}</div>'
    op = f';opacity:{alpha}' if alpha != 1 else ''
    tr = tf or f'scale({s})'
    return (f'<div class="palw" style="left:{x}px;top:{y}px;transform:{tr}{op}"><div class="pal">{head}{"".join(rows)}{extra}</div></div>')


# the pages, as drawn in the tab states (hover marks the row the ray is on)
def page_3d(hov=2, k=1):
    return [r_seg(0, '强度', ('关闭', '柔和', '标准', '强烈'), '标准'),
            r_seg(1, '片源', ('自动', '左右', '上下'), '自动'),
            r_cont(2, '漂浮感', f'{k:+d}', (k + 2) / 8, ticks=(2 / 8,), chip=('自动', True),
                   reset=k != 0, hov=hov == 2),
            r_seg(3, '层次', ('关', '浅', '中', '深'), '中', hov=hov == 3)]


def page_screen(hov=0, curve_why=None):
    rows = [r_cont(0, '大小', '中 50°', 20 / 60, ticks=(10 / 60, 20 / 60, 32 / 60, 50 / 60), hov=hov == 0),
            r_cont(1, '距离', '3.0 m', .292, ticks=(0, .292, .661), hov=hov == 1)]
    rows.append(r_why(2, '曲度', curve_why) if curve_why else
                r_cont(2, '曲度', '35%', .35, ticks=(0,), reset=True, hov=hov == 2))
    rows.append(r_seg(3, '环境', ('透视', '调暗', '影院', '纯黑'), '调暗'))
    return rows


def page_dm1(hov=1):
    return [r_seg(0, '弹幕', ('开', '关'), '开'),
            r_seg(1, '区域', ('全屏', '半屏', '1/4'), '半屏', hv='全屏' if hov == 1 else None, hov=hov == 1),
            r_cont(2, '不透明', '80%', .75, ticks=(.75,), hov=hov == 2),
            r_link(3, '更多：字号 · 速度 · 屏蔽', hov=hov == 3)]


def page_dm2(hov=2):
    return [r_seg(0, '字号', ('小', '中', '大'), '中'),
            r_seg(1, '速度', ('慢', '标准', '快'), '标准'),
            r_seg(2, '屏蔽', ('滚动', '顶部', '底部', '彩色'), None, x=('彩色',), reset=True, hov=hov == 2),
            r_link(3, '', back=True, hov=hov == 3)]


def page_sub(hov=0, bitmap=False):
    rows = [r_step(0, '字幕', '中文（图形）' if bitmap else '简体中文', hov=hov == 0),
            r_step(1, '音轨', '国语 · 5.1', hov=hov == 1)]
    rows.append(r_cont(2, '缩放', '100%', 30 / 80, ticks=(30 / 80,), hov=hov == 2) if bitmap else
                r_seg(2, '字号', ('小', '中', '大'), '中', hov=hov == 2))
    rows.append(r_cont(3, '位置', '2°', 1 / 11, ticks=(1 / 11,), hov=hov == 3))
    return rows


# ---------------------------------------------------------------- head-view pieces
def mz(x, y, t, fs=None):
    st = f';font-size:{fs}px' if fs else ''
    return f'<div class="mz" style="left:{x}px;top:{y}px{st}">{t}</div>'


def mzb(x, y, w, h):
    return f'<div class="mzb" style="left:{x}px;top:{y}px;width:{w}px;height:{h}px"></div>'


TRACK0, PITCH = 10, 36.4          # first track 0.5 deg under the picture top; pitch = 1.4 deg x 1.3
LAYER = ('远', '中', '近', '中')   # tracks cycle far, mid, near, mid: neighbours always differ by half a step

# (track, x inside the picture, text, colour)
DM_ITEMS = [
    (0, 40, '第三刷打卡', None), (0, 720, '这雾拍得太绝了', None),
    (1, 150, 'BGM 是什么', None), (1, 560, '这个构图像油画', '#8FE3B0'), (1, 930, '灯塔那段', None),
    (2, -40, '老船长好帅', None), (2, 330, '哈哈哈哈哈 ×12', None), (2, 770, '从片头追到这里', None),
    (3, 90, '有人 2026 年还在看吗', None), (3, 640, '下集预告呢', None),
    (4, 260, '镜头一直在晃', None), (4, 700, '原来他早就知道', '#FFA8BA'),
    (5, 20, '好安静的一段', None), (5, 520, '这里配乐绝了', None),
    (6, 380, '泪目', None),
]


def dm_layer(items=DM_ITEMS, top_fixed='前方高能预警', bottom_fixed='（雾里那盏灯是关键）', bottom_y=455, extra=''):
    h = ''
    for tr, x, t, col in items:
        c = f';color:{col}' if col else ''
        h += f'<span class="dmi" style="left:{x}px;top:{TRACK0 + tr * PITCH:.0f}px{c}">{t}</span>'
    if top_fixed:
        h += f'<span class="dmi fx" style="left:496px;top:{TRACK0}px;color:#FFD86B">{top_fixed}</span>'
    if bottom_fixed:
        h += f'<span class="dmi fx" style="left:496px;top:{bottom_y}px;color:#C9D2FF">{bottom_fixed}</span>'
    return f'<div class="dml">{h}{extra}</div>'


def vr(inner):
    return inner


def controller_svg(x, y, rot=0, scale=1.0, side='l'):
    """A generic controller outline: grip, a tracking ring on top, stick and two face buttons."""
    flip = -1 if side == 'r' else 1
    return (f'<svg class="diag" width="1600" height="1000" style="z-index:7"><g transform="translate({x} {y}) rotate({rot}) scale({scale * flip} {scale})">'
            '<path d="M-34 -10 C-40 40 -30 120 -12 150 L12 150 C30 120 40 40 34 -10 Z" fill="#26282c" stroke="#3a3d43" stroke-width="3"/>'
            '<ellipse cx="0" cy="-40" rx="78" ry="44" fill="none" stroke="#3a3d43" stroke-width="12"/>'
            '<ellipse cx="0" cy="-40" rx="78" ry="44" fill="none" stroke="#26282c" stroke-width="7"/>'
            '<circle cx="-6" cy="-8" r="13" fill="#1b1c1f" stroke="#45484f" stroke-width="3"/>'
            '<circle cx="18" cy="16" r="6" fill="#3a3d43"/><circle cx="6" cy="30" r="6" fill="#3a3d43"/>'
            '</g></svg>')


# ---------------------------------------------------------------- states
def st_palette_tab(kind):
    """Palette pages at 0.75 scale, with a spec card on the right."""
    X, Y, S = 60, 120, .75
    P = lambda tx, ty: (X + tx * S, Y + ty * S)
    meas, card = '', ''
    if kind == 'p3d':
        p = pal('3D', page_3d(), X, Y)
        x0, y0 = P(0, 0)
        meas = (mzb(x0 - 2, y0 - 2, 772, 580)
                + mzb(P(24, 456)[0], P(24, 456)[1], 732, 108) + mzb(P(864, 464)[0], P(864, 464)[1], 96, 96)
                + mz(60, 712, '调色板 20.0 × 15.0 cm · 贴图 1024 × 768（51.2 texel/cm）· 45 cm 处 25° × 19°')
                + mz(60, 742, '行高 144 = 2.81 cm（45 cm 处 3.6°，55 cm 处 2.9°）· 每页最多 4 行')
                + mz(60, 772, '最小目标 128 = 2.5 cm（55 cm 处 2.6°）· 相邻目标间隔 ≥ 24 = 0.47 cm')
                + mz(60, 802, '字 56 = 1.09 cm（55 cm 处 1.14°，45 cm 处 1.39°）· 数值 60 / 600')
                + mz(60, 832, '复位钮 128 × 128 · 只在改过默认值的行出现'))
        card = ('<h3>3D：强度、片源、漂浮感、层次</h3>'
                '<p>漂浮感和层次放在 3D 页，因为它们本身就是纵深，而且和「强度」联动：强度越高，画面凸得越多，'
                '「自动」漂浮感跟着往前。另一个原因：弹幕和字幕共用漂浮感，没有弹幕的片源也要能调字幕的深度。</p>'
                '<table><tr><td>强度</td><td>关闭 / 柔和 / 标准 / 强烈 · <b>只改这部片子</b></td></tr>'
                '<tr><td>片源</td><td>自动 / 左右 / 上下 · 只改这部片子</td></tr>'
                '<tr><td>漂浮感</td><td>「自动」开：跟着画面最靠前的内容，再 −2…+6 格（每格 0.05°）<br>'
                '「自动」关：固定 0–0.60°，显示成当前距离下的厘米数，0 = 贴屏</td></tr>'
                '<tr><td>层次</td><td>关 0 / 浅 0.15° / <b>中 0.30°</b> / 深 0.45° · 弹幕分三层的总跨度</td></tr></table>'
                '<h4>怎么改值</h4><p>射线停在一行上（这一行提亮，数值两边出现 ‹ ›），推<b>右手</b>摇杆左右：一格一振（5 ms / 0.2），'
                '经过有名字的档位重振一下（15 ms / 0.5）并停 300 ms，到头连振两下。按住 400 ms 后每秒 8 格。'
                '按下摇杆 = 复位这一行。滑杆照样可以拖，作为后备。</p>'
                '<h4>存在哪里</h4><p>强度、片源、字幕轨、音轨跟着片子存，复位回到设置里的默认值；其余（屏幕、漂浮感、弹幕外观…）'
                '是一份全局值，和设置页是同一个值，复位回到出厂值。第一次在一部片子里改强度时出一条提示「只改这部片子」。</p>')
    elif kind == 'pscreen':
        p = pal('屏幕', page_screen(hov=0), X, Y)
        meas = (mz(60, 712, '大小 30–90°，档位 小 40 / 中 50 / 大 62 / 巨幕 80（刻度线）')
                + mz(60, 742, '距离 2–8 m，对数刻度，档位 2 / 3 / 5 m')
                + mz(60, 772, '曲度 0–100%（每格 5%），0 = 平，100% = 半径等于观看距离'))
        card = ('<h3>屏幕：大小、距离、曲度、环境</h3>'
                '<table><tr><td>大小</td><td>按视角定，显示「中 50°」。改大小不改距离</td></tr>'
                '<tr><td>距离</td><td>只改立体纵深，看起来一样大（大小按角度定），寸数跟着变</td></tr>'
                '<tr><td>曲度</td><td>连续值 c：半径 R = 距离 / c，角宽度不变。c = 0 时是平面 quad；'
                '设置里原来的「曲面屏幕」开关改成这个值的默认值（出厂 0%）</td></tr>'
                '<tr><td>环境</td><td>透视 / 调暗 / 影院 / 纯黑（原来的「环境」标签并到这里）</td></tr></table>'
                '<h4>不能用时</h4><p>头显不支持柱面层：曲度行变灰，写「这台头显不支持曲面」。在影院房间里：写「影院房间里屏幕是平的」，'
                '记住的值不丢，离开房间后恢复。</p>'
                '<h4>去掉了「放到正前方」</h4><p>长按 PICO 键就是系统的重新居中，App 跟着把画面放回正前方；'
                '头部放不下第三个按钮，没必要重复。</p>'
                '<h4>不用调色板也能改</h4><p>射线指着画面，<b>按住扳机推摇杆</b>：前后 = 远近，左右 = 大小（见「屏幕操控」）。</p>')
    elif kind == 'pdm':
        p = pal('弹幕', page_dm1(), X, Y)
        meas = (mz(60, 712, '第一页放看片中途会改的：开关、区域、不透明度')
                + mz(60, 742, '第二页放设一次就不动的：字号、速度、屏蔽')
                + mz(60, 772, '左手摇杆上下 = 翻页；切到别的标签再回来，回到第一页'))
        card = ('<h3>弹幕 · 第一页</h3>'
                '<table><tr><td>弹幕</td><td>开 / 关。也可以按 Y，或点控制条上的「弹」</td></tr>'
                '<tr><td>区域</td><td>全屏 / <b>半屏</b> / 1/4（滚动和顶部弹幕的范围）</td></tr>'
                '<tr><td>不透明</td><td>20–100%，每格 10%，默认 80%。渲染时统一乘，改了立刻对屏幕上所有弹幕生效</td></tr>'
                '<tr><td>更多</td><td>字号 · 速度 · 屏蔽（第二页）</td></tr></table>'
                '<h4>只在设置里</h4><p>密度（稀疏 / 适中 / 密集）、合并重复、弹幕曲度（跟随屏幕 / 平 / 自定义）。'
                '漂浮感和层次在 3D 页。</p>'
                '<h4>弹幕关着时</h4><p>第 2–4 行变灰，不能改；第一行照常。</p>'
                '<h4>调的时候看得见</h4><p>手上的调色板在视野下方，看它时画面在视野上沿外面。调弹幕时把调色板钉到画面旁边'
                '（点右上角图钉），一边调一边看。屏幕上没有弹幕或在暂停时，打开这一页会在显示区域里摆三条静止的「弹幕预览」，一层一条。</p>')
    elif kind == 'pdm2':
        p = pal('弹幕', page_dm2(), X, Y)
        meas = (mz(60, 712, '屏蔽：多选，选中 = 不显示（划线）· 例：屏蔽彩色')
                + mz(60, 742, '第 4 行固定是「‹ 返回」，和第一页「更多」在同一个位置'))
        card = ('<h3>弹幕 · 第二页</h3>'
                '<table><tr><td>字号</td><td>小 1.2° / <b>中 1.4°</b> / 大 1.7°。只影响之后出现的弹幕（已经画进图集的不变）</td></tr>'
                '<tr><td>速度</td><td>横穿画面 慢 10 s / <b>标准 8 s</b> / 快 6 s，角速度最高 12°/s</td></tr>'
                '<tr><td>屏蔽</td><td>滚动 / 顶部 / 底部 / 彩色，可多选。改了之后屏幕上属于这类的弹幕立刻去掉（不做淡出）</td></tr></table>'
                '<h4>为什么第二页只有 3 行</h4><p>每页最多 4 行（射线从另一只手来，行再密就点不准），第 4 行要留给「返回」。'
                '放不下的密度、合并重复、曲度进设置页。</p>')
    else:  # psub
        p = pal('字幕音轨', page_sub(hov=0), X, Y)
        meas = (mz(60, 712, '字幕轨、音轨用 ‹ 值 › 步进：项多也不用列表')
                + mz(60, 742, '只有一条音轨时，那一行变灰，写「只有一条音轨」'))
        card = ('<h3>字幕音轨</h3>'
                '<table><tr><td>字幕</td><td>关闭 / 各条字幕（内封、外挂、插件给的）。跟着片子存</td></tr>'
                '<tr><td>音轨</td><td>各条音轨。跟着片子存</td></tr>'
                '<tr><td>字号</td><td>小 1.3° / <b>中 1.6°</b> / 大 2.0°</td></tr>'
                '<tr><td>位置</td><td>画面下沿上方 1–12°，每格 0.5°，默认 2°</td></tr></table>'
                '<h4>只在设置里</h4><p>默认字幕语言、背景（无底 / 半透明底）、字幕曲度（跟随屏幕 / 平 / 自定义）。</p>'
                '<h4>字幕的深度</h4><p>没有单独的设置：和弹幕共用 3D 页的「漂浮感」，字幕在最后一层（漂浮感那一层），'
                '只是按自己的字幕框测画面深度。</p>'
                '<h4>图形字幕（PGS 等）</h4><p>只能缩放和挪位置：「字号」换成「缩放 70–150%」，设置里的「背景」变灰（写「图形字幕自带样式」）。'
                '漂浮感和曲度照常。</p>'
                '<h4>标签名</h4><p>「字幕与音轨」五个字放不进标签栏（56 px 字，四个标签加图钉共 1000 px），写成「字幕音轨」。</p>')
    body = '<div class="bg-plain" style="opacity:.0"></div>' + p + meas
    body += f'<div class="spec" style="left:880px;top:84px;width:690px">{card}</div>'
    return body


def st_special():
    s = .36
    xs = (32, 421, 810, 1199)
    y = 150
    a = pal('弹幕', [r_msg(0, '这个片源不提供弹幕', strong=True), r_msg(1, '插件声明了弹幕能力才有')], xs[0], y, s)
    b = pal('屏幕', page_screen(hov=-1, curve_why='影院房间里屏幕是平的'), xs[1], y, s)
    c = pal('字幕音轨', page_sub(hov=2, bitmap=True), xs[2], y, s)
    d = pal('3D', page_3d(hov=3, k=0), xs[3], y, s, hb='close')
    caps = [
        ('片源没有弹幕', '标签还在（位置不跳），内容写原因。控制条上的「弹」不出现，按 Y 出提示「这个片源没有弹幕」。'
                     '加载失败时第一行换成「弹幕没加载出来」加「重试」。电脑串流时整个标签没有：3D / 屏幕 / 串流。'),
        ('曲度不能用', '影院房间：「影院房间里屏幕是平的」；头显不支持柱面层：「这台头显不支持曲面」。没有复位钮，记住的值不丢。'),
        ('图形字幕', '「字号」换成「缩放」（70–150%）。背景在设置里变灰：「图形字幕自带样式」。位置、漂浮感、曲度照常。'),
        ('手势追踪', '调色板总是钉住，从控制条「调整」打开，在视线下方 20°、0.5 m。图钉换成 ×。捏住一行左右拖改值，每 8 mm 一格；'
                 '捏一下滑杆直接跳到那里。'),
    ]
    out = a + b + c + d
    for x, (t, n) in zip(xs, caps):
        out += f'<div class="cap" style="left:{x}px;top:{y + 290}px;width:369px"><b>{t}</b><br>{n}</div>'
    out += mz(xs[3] + 300, y - 30, '× 代替图钉')
    out += mz(32, 110, '四种特殊情况 · 贴图按 0.36 缩放')
    return out


def head_view_base(dm=True, sub=True, sub_top=716, sub_box=False):
    h = screen()
    if dm:
        h += dm_layer()
    if sub:
        h += subt(sub_top) if not sub_box else f'<div class="subt box" style="top:{sub_top}px"><span>雾再大，灯也得亮着。</span></div>'
    return h


def st_hand():
    # gaze pitched ~28 deg down: the picture slides up by 560 px, only its lower edge is in view
    up = 560
    h = (f'<div class="screen" style="top:{221 - up}px">{scene("harbor")}</div>'
         f'<div class="subt" style="top:{716 - up}px">雾再大，灯也得亮着。</div>')
    h += controller_svg(560, 905, rot=-12, scale=1.0)
    tf = 'perspective(1600px) rotateX(24deg) rotateY(10deg) rotateZ(4deg) scale(.5)'
    h += pal('3D', page_3d(hov=2), 318, 440, tf=tf)
    h += ray(640, 702)
    h += (mz(860, 300, '调色板 20 × 15 cm，挂在左手：握把上方 8 cm，朝身体中线偏 2 cm')
          + mz(860, 330, '距眼 35–55 cm（常用 45 cm）→ 25° × 19°，比控制条近得多')
          + mz(860, 360, '相对握把后仰约 40°，再朝眼睛修正最多 20°（100 ms 低通）')
          + mz(860, 390, '背向眼睛超过 60°：淡出，不接受输入')
          + mz(860, 440, '右手射线指行 · 右手摇杆左右改值 · 按下复位')
          + mz(860, 470, '左手摇杆左右切标签 · 上下翻页 · ≡ 收起')
          + mz(860, 500, '左手的射线、扳机、握把这时不工作（它托着调色板）')
          + mz(860, 550, '右手握把在调色板上按住 → 从手上拿下来，松开处钉住'))
    h += mz(330, 190, '看调色板时画面在视野上沿：调弹幕时钉到画面旁边更好')
    return h


def st_mount():
    sv = ('<svg class="diag" width="1600" height="1000">'
          '<defs><marker id="ah" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse">'
          '<path d="M0 0 L10 5 L0 10 z" fill="#7FD7FF"/></marker></defs>'
          # head
          '<circle cx="250" cy="250" r="92" fill="#2a2c31"/><rect x="300" y="215" width="70" height="54" rx="14" fill="#1b1c1f"/>'
          '<circle cx="340" cy="242" r="6" fill="#ECECEE"/>'
          # sight line to the panel centre (45 cm at 12 px/cm = 540 px)
          '<line x1="340" y1="242" x2="752" y2="590" stroke="#7FD7FF" stroke-width="2" stroke-dasharray="8 6" marker-end="url(#ah)"/>'
          '<text x="520" y="392" fill="#7FD7FF" font-size="18" transform="rotate(40 520 392)">45 cm（35–55）</text>'
          # panel: 15 cm tall = 180 px, perpendicular to the sight line (sight at ~40 deg down)
          '<g transform="translate(760 596) rotate(-50)"><rect x="-90" y="-7" width="180" height="14" rx="5" fill="#ECECEE"/></g>'
          '<text x="830" y="470" fill="#ECECEE" font-size="20" font-weight="600">调色板 15 cm 高（侧面）</text>'
          # controller
          '<g transform="translate(700 760) rotate(-18)"><path d="M-26 0 C-30 50 -22 130 -10 160 L10 160 C22 130 30 50 26 0 Z" fill="#3a3d43"/>'
          '<ellipse cx="0" cy="-20" rx="46" ry="16" fill="none" stroke="#55585f" stroke-width="10"/></g>'
          '<circle cx="700" cy="790" r="7" fill="#EE8577"/><text x="560" y="840" fill="#EE8577" font-size="18">握把原点</text>'
          # 8 cm offset from grip origin to panel bottom edge
          '<line x1="700" y1="790" x2="704" y2="666" stroke="#7FD7FF" stroke-width="2" marker-end="url(#ah)"/>'
          '<text x="560" y="730" fill="#7FD7FF" font-size="18">8 cm，避开环</text>'
          # tilt arc
          '<path d="M820 760 A120 120 0 0 0 880 650" fill="none" stroke="#7FD7FF" stroke-width="2"/>'
          '<text x="890" y="720" fill="#7FD7FF" font-size="18">相对握把后仰约 40°</text>'
          '<text x="890" y="746" fill="#7FD7FF" font-size="18">再朝眼睛修正 ≤ 20°（100 ms 低通）</text>'
          '<text x="890" y="772" fill="#7FD7FF" font-size="18">背向眼睛 &gt; 60° 淡出，停止输入</text>'
          '</svg>')
    card = ('<h3>挂在手柄上</h3>'
            '<p>参照 Open Brush：调色板固定在左手握把的姿态上，手一抬就到眼前，放下就离开视野，不用找。</p>'
            '<table><tr><td>位置</td><td>下沿在握把原点上方约 8 cm（不压住环），朝身体中线偏 2 cm</td></tr>'
            '<tr><td>角度</td><td>相对握把后仰约 40°；再按眼睛方向修正，最多 20°，100 ms 低通，免得手抖时面板跟着抖</td></tr>'
            '<tr><td>距离</td><td>自然抬手时 35–55 cm，常用 45 cm</td></tr>'
            '<tr><td>失效</td><td>面板法线和视线夹角 &gt; 60°：淡出，射线打不中</td></tr>'
            '<tr><td>换手</td><td>设置 › 手柄与手势 › 调色板挂在 左手 / 右手（镜像）</td></tr></table>'
            '<p>这些数都是估计值，要在头显上调（§8）。</p>'
            '<h4>钉住</h4><p>点图钉：留在当前位置，转成竖直、正对头部。右手握把按住可以拖。再点一次图钉，200 ms 飞回手上。'
            '下次按 ≡：上次钉住的位置还在视线 ±60° 内就在原处打开，否则回到手上。</p>')
    return sv + f'<div class="spec" style="left:1060px;top:84px;width:510px">{card}</div>'


def st_pinned():
    # the bottom danmaku would sit above the subtitle, which has moved up here; leave it out of this picture
    h = screen() + dm_layer(bottom_fixed=None)
    # pinned at ~0.6 m: 20 cm wide = 19 deg = 380 px
    h += pal('弹幕', page_dm1(hov=2), 900, 560, s=.37, hb='pinned')
    h += ('<div class="mzb" style="left:560px;top:712px;width:480px;height:48px;border-style:dotted;opacity:.8"></div>'
          + mz(560, 766, '字幕原来的位置（和调色板重叠）'))
    h += f'<div class="subt" style="top:496px">雾再大，灯也得亮着。</div>'
    h += ray(1236, 760)
    h += (mz(1290, 520, '钉住：竖直、正对头部')
          + mz(1290, 548, '右手握把按住可拖动')
          + mz(1290, 576, '左右手摇杆都能用：')
          + mz(1290, 604, '空着的手左右 = 切标签')
          + mz(330, 540, '字幕抬到调色板上沿以上 1°（滞回 1°，200 ms 移过去）')
          + mz(304, 190, '弹幕从调色板后面经过：调色板更近（约 0.5 m），层也排在弹幕之后，深度和遮挡一致'))
    return h


def st_bar():
    h = head_view_base()
    h += bar(dm='on', src='小站', dm_hover=True)
    h += ray(1040, 876)
    # zoomed glyphs
    h += ('<div class="abs" style="left:1310px;top:110px;width:260px;height:290px;z-index:30;background:rgba(18,18,20,.86);border-radius:12px"></div>'
          '<div class="vround dmb on" style="left:1330px;top:130px;transform:scale(2);transform-origin:0 0;z-index:31"><span>弹</span></div>'
          '<div class="vround dmb off" style="left:1446px;top:130px;transform:scale(2);transform-origin:0 0;z-index:31"><span>弹</span><i></i></div>'
          '<div class="cap" style="left:1330px;top:254px;width:230px">左：开（琥珀色「弹」= 已开启）<br>右：关（灰字加斜线）<br>2.8° 圆，和退出键一样大</div>')
    h += (mz(980, 780, '弹幕开关：只在片源支持弹幕时出现')
          + mz(304, 780, '进度条 27.2° → 23.2°，给开关让位；片源没有弹幕时按钮不出现，进度条恢复原长')
          + mz(1206, 846, '和「调整」间隔 1.2°'))
    return h


def st_resize():
    h = head_view_base()
    h += '<div class="ring2" style="left:900px;top:420px"></div>'
    h += ray(900, 420)
    # readout in the toast slot: 24 x 2.8 deg
    ticks = ''.join(f'<i style="position:absolute;left:{p * 100:.1f}%;top:-6px;width:3px;height:20px;margin-left:-1px;border-radius:2px;background:#fff"></i>'
                    f'<em style="position:absolute;left:{p * 100:.1f}%;top:16px;transform:translateX(-50%);font-style:normal;font-size:14px;color:var(--text-2)">{t}</em>'
                    for p, t in ((0, '2'), (.292, '3'), (.661, '5')))
    h += ('<div class="vp" style="left:560px;top:830px;width:480px;height:56px;border-radius:28px;display:flex;align-items:center;white-space:nowrap;'
          f'gap:12px;padding:0 22px;font-size:22px;font-weight:500">{ic("dist", 24)}<b style="font-weight:600">3.4 m</b>'
          '<span class="c2">· 中 50° · 143 寸</span>'
          '<span style="position:relative;display:block;width:110px;height:6px;margin-left:auto;margin-top:-12px;border-radius:3px;background:rgba(255,255,255,.22)">'
          f'{ticks}<b style="position:absolute;left:38.3%;top:-8px;width:12px;height:22px;margin-left:-6px;border-radius:6px;background:var(--text)"></b></span></div>')
    h += mz(560, 894, '提示槽位 24° × 2.8° · 贴图 960 × 112 · 数字变了才重画')
    # size variant, drawn off to the side: in the headset it takes the same slot
    h += ('<div class="vp" style="left:60px;top:830px;width:400px;height:56px;border-radius:28px;display:flex;align-items:center;white-space:nowrap;'
          f'gap:12px;padding:0 22px;font-size:22px;font-weight:500;opacity:.9">{ic("size", 24)}<b style="font-weight:600">大 62°</b>'
          '<span class="c2">· 3.0 m · 163 寸</span></div>'
          + mz(60, 894, '左右推时换成这个（同一个位置）'))
    h += (mz(940, 380, '射线指着画面 · 扳机按住（光标变成圆环）')
          + mz(940, 408, '摇杆前后：远近（向前 = 推远）· 左右：大小')
          + mz(940, 436, '先过阈值的那根轴锁定，回中才换')
          + mz(940, 464, '经过档位：停 300 ms + 振 15 ms；松开时离档位很近就吸上去')
          + mz(940, 492, '刻度 = 档位：距离 2 / 3 / 5 m，大小 40 / 50 / 62 / 80°')
          + mz(940, 520, '松开扳机 = 保存（不暂停）· 按 B = 撤销')
          + mz(304, 190, '控制条隐藏；调色板开着时降到 40% 不透明（改层的透明度，不重画）'))
    return h


def st_trigger():
    X0, PX = 380, .72   # timeline origin, px per ms (0..1200 ms)
    def t(ms):
        return X0 + ms * PX
    rows = [
        ('单击', [('trig', 0, 200)], [], '播放 / 暂停', '350 ms 内松开（这里 200 ms），摇杆一直在死区（0.25）里'),
        ('按住不推', [('trig', 0, 900)], [], '什么都不做', '350 ms 出提示「推摇杆：前后调远近 · 左右调大小」+ 轻振'),
        ('按下就推', [('trig', 0, 1000), ('adj', 120, 1000)], [('stk', 120, 900)], '调整，松开保存', '摇杆过 0.5 就进入调整，不用等 350 ms'),
        ('按住再推', [('trig', 0, 1100), ('adj', 600, 1100)], [('stk', 600, 1000)], '调整，松开保存', '这一次不再算单击'),
        ('先推后按', [('trig', 300, 1100), ('adj', 760, 1100)], [('stk', 0, 520), ('stk', 760, 1050)], '回中之后才算',
         '按扳机时摇杆已经偏着（在快进）：这次推动不算，回中后再推才进入调整'),
        ('中途离开', [('trig', 0, 280)], [], '取消', '调整开始前射线离开画面：不暂停也不调整'),
        ('调整中按 B', [('trig', 0, 1000), ('adj', 200, 760)], [('stk', 200, 700)], '撤销', '回到按下扳机前的大小和距离'),
    ]
    h = '<div class="bg-plain"></div>'
    h += f'<div class="cap" style="left:60px;top:70px;font-size:22px"><b>扳机 + 摇杆的判定</b>（射线在画面上、不在控件上）</div>'
    # axis
    h += f'<div class="abs" style="left:{t(0)}px;top:140px;width:{1200 * PX}px;height:2px;background:#555;z-index:11"></div>'
    for ms in range(0, 1201, 200):
        h += f'<div class="tlab" style="left:{t(ms) - 14}px;top:112px">{ms}</div>'
    h += f'<div class="tlab" style="left:{t(1200) + 36}px;top:112px">ms</div>'
    h += (f'<div class="abs" style="left:{t(350)}px;top:140px;width:2px;height:{len(rows) * 92 + 20}px;background:#7FD7FF;z-index:11;opacity:.8"></div>'
          f'<div class="mz" style="left:{t(350) - 48}px;top:150px">350 ms</div>')
    y = 194
    for name, bars, sticks, res, why in rows:
        h += f'<div class="tres" style="left:60px;top:{y + 4}px">{name}</div>'
        for kind, a, b in bars:
            if kind == 'adj':
                h += f'<div class="tl adj" style="left:{t(a)}px;top:{y - 6}px;width:{(b - a) * PX}px"></div>'
            else:
                h += f'<div class="tl trig" style="left:{t(a)}px;top:{y + 3}px;width:{(b - a) * PX}px"></div>'
        for kind, a, b in sticks:
            h += f'<div class="tl stk" style="left:{t(a)}px;top:{y + 34}px;width:{(b - a) * PX}px"></div>'
        h += f'<div class="tres" style="left:{t(1200) + 50}px;top:{y + 4}px">{res}</div>'
        h += f'<div class="tlab" style="left:{t(0)}px;top:{y + 54}px;color:#a6a9b0">{why}</div>'
        y += 92
    h += (f'<div class="cap" style="left:60px;top:{y + 6}px;width:880px">'
          '<span style="display:inline-block;width:40px;height:12px;border-radius:6px;background:rgba(242,242,242,.82);margin-right:8px"></span>扳机按着'
          '<span style="display:inline-block;width:40px;height:10px;border-radius:5px;background:#8E949E;margin:0 8px 0 24px"></span>摇杆过 0.5'
          '<span style="display:inline-block;width:40px;height:16px;border-radius:4px;background:rgba(127,215,255,.28);margin:0 8px 0 24px"></span>调整中'
          '　·　扳机在控件上按下永远是点控件；没按扳机时摇杆左右 = 快进快退，射线停在调色板某一行上时 = 改那一行（由射线位置决定，不由时间决定）。'
          '握把按住 + 摇杆和扳机按住 + 摇杆一样：前后远近、左右大小。</div>')
    return h


def st_dmflat():
    h = screen() + dm_layer() + subt()
    # layer tags left of the picture
    dist = {'远': '2.40 m', '中': '2.18 m', '近': '2.00 m'}
    for tr in range(7):
        L = LAYER[tr % 4]
        h += mz(170, 221 + TRACK0 + tr * PITCH + 6, f'{L} {dist[L]}', fs=13)
    h += ('<div class="mzl" style="left:1306px;top:221px;width:2px;height:279px"></div>'
          + mz(1316, 230, '显示区域：半屏')
          + mz(1316, 258, '7 条轨道（50° 画面 · 字号 1.4°）')
          + mz(1316, 286, '轨道间距 1.82° = 字号 × 1.3')
          + mz(1316, 314, '从上往下找空轨道')
          + mz(1316, 370, '轨道按 远 中 近 中 循环：')
          + mz(1316, 398, '相邻两行只差半档')
          + mz(1316, 454, '标准速度：8 s 横穿 ≈ 7.5°/s')
          + mz(1316, 660, '底部弹幕：居中停 4 s，')
          + mz(1316, 688, '排在字幕上方，最多 3 行')
          + mz(560, 190, '顶部弹幕：居中停 4 s，在最近一层，滚动弹幕从它后面过')
          + mz(40, 640, '两侧 1.5° 固定羽化')
          + mz(40, 668, '（层的属性，不是逐条动画）')
          + mz(560, 790, '字幕：思源黑体 500 · 1.6° · 在最后一层（漂浮感那一层）'))
    h += mz(40, 520, '例：3 m、3D 标准、', fs=13) + mz(40, 544, '漂浮感自动、层次中', fs=13)
    return h


def st_dmcurve():
    # third-person sketch: camera behind and above the viewer, screen curved around the viewer
    def lerp(a, b, f):
        return a + (b - a) * f
    items = [(0, 50, '前方高能预警', '#FFD86B'),
             (1, 4, '这雾拍得太绝了', None), (1, 58, '第三刷打卡', None),
             (2, 14, 'BGM 是什么', None), (2, 40, '这个构图像油画', '#8FE3B0'), (2, 78, '灯塔那段', None),
             (3, 2, '哈哈哈哈哈 ×12', None), (3, 50, '从片头追到这里', None),
             (4, 22, '有人 2026 年还在看吗', None), (4, 70, '下集预告呢', None)]
    rows_svg = ''
    for k in range(5):
        f = .05 + k * .09
        xl, xr = lerp(200, 230, f), lerp(1180, 1150, f)
        yl, yc = lerp(300, 640, f) + 24, lerp(150, 520, f) + 24
        rows_svg += f'<path id="dr{k}" d="M{xl:.0f} {yl:.0f} Q690 {yc:.0f} {xr:.0f} {yl:.0f}" fill="none"/>'
    for k, off, txt, col in items:
        anchor = ' text-anchor="middle"' if k == 0 else ''
        rows_svg += (f'<text font-size="25" font-weight="700" fill="{col or "#F3F3F1"}" fill-opacity=".85" stroke="#0E0F12" stroke-width="3" '
                     f'paint-order="stroke"{anchor}><textPath href="#dr{k}" startOffset="{off}%">{txt}</textPath></text>')
    sv = ('<svg class="diag" width="1600" height="1000">'
          '<defs><linearGradient id="scr" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#8d9aa6"/><stop offset=".55" stop-color="#5d6a76"/>'
          '<stop offset="1" stop-color="#2d343b"/></linearGradient>'
          '<marker id="ah2" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="8" markerHeight="8" orient="auto-start-reverse"><path d="M0 0 L10 5 L0 10 z" fill="#7FD7FF"/></marker></defs>'
          '<rect width="1600" height="1000" fill="#0f1013"/>'
          '<path d="M200 300 Q690 150 1180 300 L1150 640 Q690 520 230 640 Z" fill="url(#scr)"/>'
          '<path d="M200 300 Q690 150 1180 300" fill="none" stroke="#c9d0d6" stroke-opacity=".35" stroke-width="2"/>'
          + rows_svg +
          '<path d="M1160 225 Q690 50 220 225" fill="none" stroke="#7FD7FF" stroke-width="2.5" stroke-dasharray="10 8" marker-end="url(#ah2)"/>'
          '<text x="690" y="116" font-size="18" fill="#7FD7FF" text-anchor="middle">沿同轴柱面，从画面右缘绕到左缘</text>'
          # viewer from behind
          '<ellipse cx="690" cy="930" rx="150" ry="70" fill="#1c1d21"/><circle cx="690" cy="840" r="58" fill="#24262b"/>'
          '<rect x="642" y="814" width="96" height="40" rx="12" fill="#1a1b1e"/>'
          '</svg>')
    # top-down inset (curvature 60%): screen radius R = D / c, so the axis sits behind the eye; danmaku coaxial, in front
    inset = ('<svg class="diag" width="1600" height="1000" style="z-index:31">'
             '<rect x="1190" y="84" width="380" height="304" rx="12" fill="rgba(18,18,20,.9)"/>'
             '<text x="1210" y="114" fill="#fff" font-size="17" font-weight="600">俯视（曲度 60%）</text>'
             '<path d="M1250 190 A200 200 0 0 1 1510 190" fill="none" stroke="#8d9aa6" stroke-width="6"/>'
             '<path d="M1263 205 A180 180 0 0 1 1497 205" fill="none" stroke="#F3F3F1" stroke-width="3" stroke-dasharray="14 8"/>'
             '<path d="M1240 190 L1520 190" fill="none" stroke="#7FD7FF" stroke-width="1.5" stroke-dasharray="4 5"/>'
             '<circle cx="1380" cy="262" r="6" fill="#ECECEE"/><text x="1392" y="268" fill="#a6a9b0" font-size="14">眼睛</text>'
             '<path d="M1374 342 L1386 342 M1380 336 L1380 348" stroke="#a6a9b0" stroke-width="2"/>'
             '<text x="1370" y="348" fill="#a6a9b0" font-size="14" text-anchor="end">柱面的轴：R = 距离 ÷ 曲度</text>'
             '<text x="1206" y="150" fill="#a6a9b0" font-size="14">画面</text>'
             '<text x="1300" y="234" fill="#F3F3F1" font-size="14">弹幕（同轴，在前面）</text>'
             '<text x="1206" y="376" fill="#7FD7FF" font-size="14">蓝虚线：选「平」时，弹幕面过画面两侧边</text>'
             '</svg>')
    card = ('<h3>弹幕跟随曲面（默认）</h3>'
            '<p>弹幕的两层（左右眼）用和画面同轴的柱面层，按角度横穿：从右缘进，沿弧绕到左缘出。</p>'
            '<p><b>弹幕面任何地方都不在画面后面：</b>比画面平（选「平」或更小的自定义曲度）时过画面两侧边，比画面弯时过画面中心。'
            '视差加在这之上。</p>'
            '<p>选项在设置里：跟随屏幕 / 平 / 自定义。影院房间里、不支持柱面层的头显上都是平的，这一项变灰。</p>')
    return sv + inset + f'<div class="spec" style="left:1190px;top:404px;width:380px">{card}</div>'


def st_dmrules():
    h = '<div class="bg-plain"></div>'
    # three mini screens, 1 deg = 6.4 px (50 deg = 320 px)
    S = 6.4
    for k, (name, frac, n, note) in enumerate((('全屏', 1, 11, '到字幕区上沿为止（有字幕时留出字幕区）'),
                                                ('半屏（默认）', .5, 7, '平视附近到画面上沿'),
                                                ('1/4', .25, 3, '只剩画面上沿，平视以上 7–14°'))):
        x, y, w, hgt = 60 + k * 360, 120, 320, 180
        area = hgt * frac if frac < 1 else hgt - 46
        lines = ''.join(f'<i style="position:absolute;left:{10 + (j * 53) % 120}px;top:{4 + j * 11.6:.0f}px;width:{60 + (j * 37) % 70}px;height:5px;'
                        f'border-radius:3px;background:rgba(243,243,241,.85)"></i>' for j in range(n) if 4 + j * 11.6 < area - 4)
        lines += ''.join(f'<i style="position:absolute;left:{170 + (j * 41) % 110}px;top:{4 + j * 11.6:.0f}px;width:{40 + (j * 29) % 60}px;height:5px;'
                         f'border-radius:3px;background:rgba(243,243,241,.85)"></i>' for j in range(n) if 4 + j * 11.6 < area - 4 and j % 2 == 0)
        h += (f'<div class="abs" style="left:{x}px;top:{y}px;width:{w}px;height:{hgt}px;border-radius:4px;overflow:hidden;z-index:11">{scene("harbor")}'
              f'<div class="abs" style="left:0;top:0;width:{w}px;height:{area:.0f}px;background:rgba(127,215,255,.16);border-bottom:2px dashed #7FD7FF">{lines}</div>'
              + ('<div class="abs" style="left:110px;top:150px;width:100px;height:8px;border-radius:4px;background:#F3F3F1"></div>' if True else '')
              + '</div>'
              f'<div class="cap" style="left:{x}px;top:{y + 190}px;width:{w}px"><b>{name}</b> · {n} 条轨道<br>{note}</div>')
    h += mz(60, 84, '50° 画面、字号 1.4°，轨道数 = （区域高 − 0.5°）÷ 1.82°')
    # one track: spacing and waiting
    y0 = 470
    h += f'<div class="cap" style="left:60px;top:{y0 - 46}px;font-size:20px"><b>一条轨道里</b></div>'
    h += (f'<div class="abs" style="left:60px;top:{y0}px;width:800px;height:70px;border-radius:6px;background:#1d2026;z-index:11;overflow:hidden">'
          '<span class="dmi" style="left:24px;top:17px">镜头一直在晃</span>'
          '<span class="dmi" style="left:250px;top:17px">原来他早就知道</span>'
          '<span class="dmi" style="left:560px;top:17px">这里配乐绝了</span></div>'
          f'<div class="mzl" style="left:506px;top:{y0 + 74}px;width:114px;height:2px"></div>'
          + mz(500, y0 + 82, '间隔 ≥ 2 个字（适中）')
          + f'<div class="abs" style="left:860px;top:{y0}px;width:2px;height:70px;background:#7FD7FF;z-index:12"></div>'
          + mz(870, y0 - 30, '画面右缘')
          + f'<div class="abs" style="left:880px;top:{y0 + 17}px;z-index:12"><span class="dmi" style="position:relative;opacity:.4">下集预告呢</span></div>')
    h += mz(60, y0 + 82, '所有弹幕同一个角速度：后面的追不上前面的')
    h += mz(60, y0 + 112, '新弹幕：前一条的尾巴离右缘够远才进来；轨道全满就等，最多 1 s，还没有就丢掉')
    card = ('<h3>轨道与密度</h3>'
            '<table class="num"><tr><th></th><th>稀疏</th><th>适中（默认）</th><th>密集</th></tr>'
            '<tr><td>同屏最多</td><td>20 条</td><td>40 条</td><td>80 条</td></tr>'
            '<tr><td>同轨间隔</td><td>4 个字</td><td>2 个字</td><td>1 个字</td></tr></table>'
            '<table style="margin-top:12px"><tr><td>顶部 / 底部</td><td>居中停 4 s；顶部从区域上沿往下排，底部从字幕上方往上排（最多 3 行）；都在最近一层</td></tr>'
            '<tr><td>合并重复</td><td>5 s 内文字相同的 ≥ 3 条合成一条「哈哈哈 ×12」（按取到的那一批往后看，出现时就定好）</td></tr>'
            '<tr><td>太长</td><td>最多 40 字，且不宽于画面的 60%，超出用「…」；换行并成一行</td></tr>'
            '<tr><td>太高</td><td>轨道不高于平视以上 18°（巨幕时画面上沿到 +22°，上面那几条不用）</td></tr>'
            '<tr><td>字幕</td><td>滚动轨道不进字幕区；底部弹幕排在字幕上面</td></tr>'
            '<tr><td>暂停 / 拖进度</td><td>暂停时原地停住；拖进度时隐藏，松手后从新时间点重新排</td></tr></table>')
    h += f'<div class="spec" style="left:1140px;top:84px;width:430px">{card}</div>'
    # defaults with reasons
    why = ('<h3>默认值为什么这样取</h3>'
           '<table><tr><td>字号 1.4°</td><td>头显每度只有约 18 个像素：静止文字最小 1.1°，移动的字看起来更糊，加 25%；比字幕（1.6°）小一档，字幕仍是主角</td></tr>'
           '<tr><td>半屏</td><td>50° 画面的上半屏有 7 条轨道，够热闹的片子用；1/4 只剩 3 条，而且在平视以上 7–14°，比自然视线（俯 10–15°）高出 20° 多，要抬头看；全屏会盖住下半部的人物和字幕</td></tr>'
           '<tr><td>8 s 横穿</td><td>50° 画面上约 7.5°/s，眼睛跟得上还能读完；巨幕时按时间会太快，所以角速度封顶 12°/s</td></tr>'
           '<tr><td>不透明 80%</td><td>大画面很亮，满不透明的白字太抢；有描边，80% 在亮背景上仍读得清</td></tr>'
           '<tr><td>适中 40 条</td><td>半屏 7 条轨道、每条约 4 条可见 ≈ 28 条，40 留出顶部 / 底部和全屏的余量</td></tr></table>')
    h += f'<div class="spec" style="left:60px;top:632px;width:1040px">{why}</div>'
    return h


def st_float():
    # top-down: 1 m = 160 px, eyes at y = 920
    E, M = 920, 160
    cx = 420
    yy = lambda d: E - d * M
    hw = lambda d: d * 0.4663 * M   # tan(25 deg): half of a 50 deg picture
    def seg(d, col, w, dash='', op=1):
        return (f'<line x1="{cx - hw(d):.0f}" y1="{yy(d):.0f}" x2="{cx + hw(d):.0f}" y2="{yy(d):.0f}" stroke="{col}" stroke-width="{w}" '
                f'stroke-opacity="{op}"{(" stroke-dasharray=" + chr(34) + dash + chr(34)) if dash else ""}/>')
    sv = ['<svg class="diag" width="1600" height="1000"><rect width="1600" height="1000" fill="#0f1013"/>']
    # frustum
    sv.append(f'<line x1="{cx}" y1="{E}" x2="{cx - hw(3.15):.0f}" y2="{yy(3.15):.0f}" stroke="#3a3d43" stroke-width="1.5"/>')
    sv.append(f'<line x1="{cx}" y1="{E}" x2="{cx + hw(3.15):.0f}" y2="{yy(3.15):.0f}" stroke="#3a3d43" stroke-width="1.5"/>')
    # screen and the content popping out of it
    sv.append(seg(3.0, '#8d9aa6', 8))
    sv.append(f'<path d="M{cx - 170} {yy(3.0):.0f} C{cx - 120} {yy(3.0):.0f} {cx - 100} {yy(2.57):.0f} {cx - 50} {yy(2.57):.0f} '
              f'L{cx + 25} {yy(2.57):.0f} C{cx + 75} {yy(2.57):.0f} {cx + 95} {yy(3.0):.0f} {cx + 145} {yy(3.0):.0f}" '
              f'fill="rgba(141,154,166,.22)" stroke="#8d9aa6" stroke-width="1.5" stroke-dasharray="5 4"/>')
    # danmaku layers: three items per layer, as seen from above
    for d in (2.40, 2.18, 2.00):
        sv.append(seg(d, '#F3F3F1', 2, '3 7', .45))
        w = hw(d)
        for a, b in ((-.85, -.45), (-.2, .15), (.4, .8)):
            sv.append(f'<rect x="{cx + a * w:.0f}" y="{yy(d) - 4:.0f}" width="{(b - a) * w:.0f}" height="8" rx="4" fill="#F3F3F1" fill-opacity=".9"/>')
    # subtitle on the far layer, in the middle
    sv.append(f'<rect x="{cx - 60}" y="{yy(2.40) + 7:.0f}" width="120" height="6" rx="3" fill="#FFD86B"/>')
    sv.append(f'<text x="{cx - hw(2.40) - 10:.0f}" y="{yy(2.40) + 14:.0f}" fill="#FFD86B" font-size="15" text-anchor="end">字幕（黄）在远层</text>')
    # limit, bar, palette
    sv.append(seg(1.64, '#EE8577', 2, '8 6'))
    bw = 1.4 * 0.364 * M
    sv.append(f'<line x1="{cx - bw:.0f}" y1="{yy(1.4):.0f}" x2="{cx + bw:.0f}" y2="{yy(1.4):.0f}" stroke="#a6a9b0" stroke-width="6"/>')
    sv.append(f'<line x1="{cx - 100}" y1="{yy(0.47):.0f}" x2="{cx - 70}" y2="{yy(0.43):.0f}" stroke="#ECECEE" stroke-width="5"/>')
    # eyes
    sv.append(f'<circle cx="{cx - 6}" cy="{E}" r="5" fill="#ECECEE"/><circle cx="{cx + 6}" cy="{E}" r="5" fill="#ECECEE"/>')
    # labels, one line each
    L = [(3.0, '#8d9aa6', '画面 3.0 m', 0), (2.57, '#a6b0ba', '画面最靠前的内容 2.57 m（0.2°）', -6),
         (2.40, '#F3F3F1', '远层 2.40 m · 0.30° · 前 60 cm', 4),
         (2.18, '#F3F3F1', '中层 2.18 m · 0.45° · 前 82 cm', 4), (2.00, '#F3F3F1', '近层 2.00 m · 0.60° · 前 100 cm', 6),
         (1.64, '#EE8577', '上限 1.64 m（≤ 1.0° 且 ≥ 1.6 m）', 0),
         (1.40, '#a6a9b0', '控制条 1.4 m', 0), (0.45, '#ECECEE', '调色板约 0.45 m', 0)]
    lx = 700
    for d, col, t, dy in L:
        sv.append(f'<text x="{lx}" y="{yy(d) + 5 + dy:.0f}" fill="{col}" font-size="15">{t}</text>')
        x1 = cx + hw(d) + 6 if d > 1 else cx - 64
        sv.append(f'<line x1="{x1:.0f}" y1="{yy(d):.0f}" x2="{lx - 8}" y2="{yy(d) + dy:.0f}" stroke="{col}" stroke-opacity=".35"/>')
    sv.append('<text x="60" y="70" fill="#fff" font-size="22" font-weight="600">漂浮感与层次 · 俯视（3 m、50°、3D 标准、自动 +0、层次中）</text>')
    sv.append('<text x="60" y="100" fill="#a6a9b0" font-size="16">1 m = 160 px。深度用视差 φ 记（相对画面平面的交叉视差）：同样的 φ 在任何距离下「浮」的感觉一样。</text>')
    # layering presets
    bx, by = 60, 150
    sv.append(f'<text x="{bx}" y="{by}" fill="#fff" font-size="17" font-weight="600">层次 = 三层的总跨度 Δ</text>')
    for k, (n, dlt) in enumerate((('关 0', 0), ('浅 .15', .15), ('中 .30', .30), ('深 .45', .45))):
        x = bx + k * 64
        sv.append(f'<text x="{x}" y="{by + 30}" fill="#a6a9b0" font-size="14">{n}</text>')
        for j in range(3):
            yv = by + 120 - (j * dlt / 2) / .45 * 70
            sv.append(f'<line x1="{x}" y1="{yv:.0f}" x2="{x + 44}" y2="{yv:.0f}" stroke="#F3F3F1" stroke-width="3" stroke-opacity="{.5 + j * .25}"/>')
    sv.append(f'<text x="{bx}" y="{by + 150}" fill="#a6a9b0" font-size="14">下 = 远层，上 = 近层</text>')
    sv.append('</svg>')
    why = ('<h3>为什么不晕</h3>'
           '<p>1. 每条弹幕从出现到离开深度不变：眼睛横着追字时不用改聚散。<br>'
           '2. 按轨道分层，同一行永远同一层；轨道按 远 中 近 中 循环，相邻两行只差 Δ/2（默认 0.15°）。<br>'
           '3. 总范围在 1° 以内，最近不近于 1.6 m，永远在控制条后面。<br>'
           '4. 自动模式下整叠弹幕在画面最靠前的内容之前，不会出现「被挡住的字反而更近」。<br>'
           '5. 两侧 1.5° 羽化，减少浮在前面的字被画面边框切断（窗口冲突）。<br>'
           '6. 深度只靠视差：不做近大远小、近快远慢、近亮远暗。<br>'
           '7. 只有自己拖滑杆时，屏幕上已有的弹幕才一起移到新深度（200 ms）。</p>')
    card = ('<h3>怎么算</h3>'
            '<p><span class="k">远层 φ = 内容 + 0.10° + k × 0.05°</span><br>（自动；k = −2…+6，默认 0）<br>'
            '<span class="k">远层 φ = k × 0.05°</span><br>（固定；k = 0…12，0 = 贴屏）<br>'
            '<span class="k">中层 = 远层 + Δ/2，近层 = 远层 + Δ</span></p>'
            '<p>「内容」= 深度图在显示区域里的第 98 百分位视差：往前立刻跟，往后每秒最多退 0.05°。3D 关闭时为 0；原生 3D 片源没有深度图，按 0.25°。'
            '近层超过上限时先压缩 Δ，再压远层。</p>'
            '<table class="num"><tr><th>φ</th><th>2 m</th><th>3 m</th><th>5 m</th></tr>'
            '<tr><td>0.10°</td><td>11 cm</td><td>23 cm</td><td>61 cm</td></tr>'
            '<tr><td>0.25°</td><td>24 cm</td><td>52 cm</td><td>129 cm</td></tr>'
            '<tr><td>0.40°</td><td>36 cm</td><td>75 cm</td><td>178 cm</td></tr>'
            '<tr><td>0.60°</td><td>—</td><td>100 cm</td><td>227 cm</td></tr>'
            '<tr><td>1.00°</td><td>—</td><td>136 cm</td><td>290 cm</td></tr>'
            '<tr><td>上限</td><td>0.45° · 1.60 m</td><td>1.0° · 1.64 m</td><td>1.0° · 2.10 m</td></tr></table>'
            '<p style="color:#a6a9b0">表里是「在画面前多少」，瞳距按 63 mm。</p>')
    return (''.join(sv) + f'<div class="spec sm" style="left:360px;top:122px;width:660px">{why}</div>'
            + f'<div class="spec" style="left:1060px;top:40px;width:510px">{card}</div>')


def st_sub():
    h = '<div class="bg-plain"></div>'
    crops = (('无底（默认）', False, '雾再大，灯也得亮着。'), ('半透明底', True, '雾再大，灯也得亮着。'),
             ('两行 · 每行 ≤ 16 字，在标点处断成两行差不多长', False, '我们在这个港口等了整整三个冬天，<br>灯塔一次也没有灭过。'))
    for k, (name, box, txt) in enumerate(crops):
        x, y = 60, 110 + k * 270
        sub_html = (f'<div class="subt{" box" if box else ""}" style="left:0;width:992px;top:{150 if "<br>" not in txt else 108}px;z-index:3">'
                    + (f'<span>{txt}</span>' if box else txt) + '</div>')
        h += (f'<div class="abs" style="left:{x}px;top:{y}px;width:992px;height:230px;overflow:hidden;border-radius:4px;z-index:11">'
              f'<div class="abs" style="left:0;top:-328px;width:992px;height:558px">{scene("harbor")}</div>{sub_html}</div>'
              f'<div class="cap" style="left:{x}px;top:{y - 30}px"><b>{name}</b></div>')
    h += (mz(80, 126, '字号 1.6°（32 px）· 下沿上方 2°') + mz(80, 396, '背景 #0E0F12 · 64% · 逐行一块底'))
    card = ('<h3>静态字幕（次要）</h3>'
            '<table><tr><td>字体</td><td>思源黑体 / Noto Sans CJK SC，500；#F3F3F1；描边 0.06 em（#0E0F12 85%）+ 阴影</td></tr>'
            '<tr><td>字号</td><td>小 1.3° / 中 1.6° / 大 2.0°；最小 1.2°（VR 最小字 1.1° 再加 10%，因为背景在动）</td></tr>'
            '<tr><td>行</td><td>最多 2 行（片源本来 3 行就 3 行），每行 ≤ 16 个汉字或 42 个拉丁字母，行高 1.3</td></tr>'
            '<tr><td>位置</td><td>画面下沿上方 1–12°，默认 2°；不低于平视以下 16°</td></tr>'
            '<tr><td>深度</td><td>和弹幕共用「漂浮感」：在最后一层，按自己的字幕框测画面深度（每条字幕取头 3 帧的中位数，显示期间只往前不往后）</td></tr>'
            '<tr><td>曲度</td><td>跟随屏幕 / 平 / 自定义（设置里）</td></tr>'
            '<tr><td>和弹幕</td><td>滚动弹幕不进字幕区；底部弹幕排在字幕上面</td></tr>'
            '<tr><td>避让</td><td>钉住的调色板挡住字幕时，字幕抬到它上沿以上 1°；手上的调色板不推字幕</td></tr>'
            '<tr><td>图形字幕</td><td>只能缩放（70–150%）和挪位置；背景变灰</td></tr>'
            '<tr><td>贴图</td><td>1 层，左右眼共用（视差靠层的位置），1440 × 400；只在字幕换了或样式改了时重画；没有字幕时不提交</td></tr></table>')
    h += f'<div class="spec" style="left:1090px;top:84px;width:480px">{card}</div>'
    return h


# ---------------------------------------------------------------- page
STATES = [
    ('p3d', '3D 页', '调色板的 3D 页，贴图 1024 × 768 按 0.75 显示。射线停在「漂浮感」上：这一行提亮，数值两边出现 ‹ ›，右手摇杆左右改值。'),
    ('pscreen', '屏幕页', '屏幕页：大小和距离是连续值，经过档位有吸附和振动。曲度从开关变成 0–100% 的连续值。'),
    ('pdm', '弹幕 1', '弹幕第一页：开关、区域、不透明度，看片中途最常改的三个。第 4 行进第二页。'),
    ('pdm2', '弹幕 2', '弹幕第二页：字号、速度、屏蔽（多选，划线 = 不显示）。第 4 行返回。'),
    ('psub', '字幕音轨', '字幕和音轨合成一页。字幕的深度不单独设，和弹幕共用 3D 页的漂浮感。'),
    ('special', '特殊情况', '没有弹幕、曲度不能用、图形字幕、手势追踪：都在原位置变灰或换内容并写原因，不让标签和行跳位置。'),
    ('hand', '手上', '低头看左手：调色板挂在握把上方，面向眼睛，右手射线操作。这时画面在视野上沿，看不到弹幕。'),
    ('mount', '挂载', '侧视：调色板相对左手握把的位置和角度。数值都要在头显上调。'),
    ('pinned', '钉住', '钉住的调色板留在空中。和字幕重叠时字幕往上让；弹幕从它后面经过，不用让。'),
    ('bar', '弹幕开关', '控制条上的「弹」：一键开关，B 站用户的习惯。只在片源支持弹幕时出现。'),
    ('dmflat', '弹幕平面', '平面屏上的弹幕：半屏 7 条轨道，按 远 中 近 中 分层；顶部、底部弹幕居中停 4 秒。青色标签是标注。'),
    ('dmcurve', '弹幕曲面', '曲面屏：弹幕沿同轴柱面从右缘绕到左缘。'),
    ('dmrules', '轨道规则', '区域、间隔、等待与丢弃、密度、合并，和默认值的理由。'),
    ('float', '漂浮感', '整叠弹幕永远在画面内容之前、控制条之后。'),
    ('resize', '调整屏幕', '扳机按住 + 摇杆：前后远近、左右大小，松开保存。'),
    ('trigger', '按键判定', '单击暂停和按住调整，靠时间和摇杆阈值区分。'),
    ('sub', '静态字幕', '静态字幕降为次要：样式、行数、位置，深度和弹幕共用漂浮感。'),
]

LAYERS = {
    'hand': '10 / 16：调暗 1 · 画面 2 · 弹幕 2 · 字幕 1 · 调色板 1 · 射线 2 · 手柄 1',
    'pinned': '10 / 16：调暗 1 · 画面 2 · 弹幕 2 · 字幕 1 · 调色板 1 · 射线 2 · 手柄 1',
    'bar': '10 / 16：调暗 1 · 画面 2 · 弹幕 2 · 字幕 1 · 控制条 1 · 射线 2 · 手柄 1',
    'dmflat': '6 / 16：调暗 1 · 画面 2 · 弹幕 2（左右眼各一）· 字幕 1',
    'dmcurve': '6 / 16：调暗 1 · 画面 2（柱面）· 弹幕 2（柱面）· 字幕 1',
    'resize': '10 / 16：调暗 1 · 画面 2 · 弹幕 2 · 字幕 1 · 读数 1 · 射线 2 · 手柄 1（调色板开着 +1）',
}


def palette_page():
    S = {}
    for k in ('p3d', 'pscreen', 'pdm', 'pdm2', 'psub'):
        S[k] = st_palette_tab(k)
    S['special'] = st_special()
    S['hand'] = st_hand()
    S['mount'] = st_mount()
    S['pinned'] = st_pinned()
    S['bar'] = st_bar()
    S['dmflat'] = st_dmflat()
    S['dmcurve'] = st_dmcurve()
    S['dmrules'] = st_dmrules()
    S['float'] = st_float()
    S['resize'] = st_resize()
    S['trigger'] = st_trigger()
    S['sub'] = st_sub()
    body = sprite() + ROOM + '<div class="vr"><div class="dimmer"></div>'
    for sid, html in S.items():
        # ray() always names its gradient "rg"; give each state its own id so a hidden state's copy is never the one used
        html = html.replace('id="rg"', f'id="rg-{sid}"').replace('url(#rg)', f'url(#rg-{sid})')
        body += f'<div data-st="{sid}">{html}</div>'
    body += '</div>'
    links = ''.join(f'<a href="#{sid}">{label}</a>' for sid, label, _ in STATES)
    body += ('<div class="anno t"><b>VR 调色板 · 弹幕 · 屏幕操控</b> <span>· 调色板按贴图像素画；头显视野 80° × 50°，1° = 20 px</span></div>'
             '<a class="anno home" href="index.html">总览</a>'
             + ''.join(f'<div class="anno layers" data-st="{k}">图层 {v}</div>' for k, v in LAYERS.items()) +
             f'<div class="anno st">状态：{links}</div>'
             + ''.join(f'<div class="anno note" data-st="{sid}">{n}</div>' for sid, _, n in STATES))
    return page('depth3d · VR 调色板与弹幕', body, 'p3d', CSS)

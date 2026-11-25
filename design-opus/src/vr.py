"""VR playback view. The canvas is the headset view: 80 x 50 degrees, 1 degree = 20 px,
gaze centre at (800, 500). Every size in here is meant to be read in degrees."""
from lib import ic, scene, page, ROOM

VR_CSS = """
.vr{position:absolute;inset:0}
.vr .dimmer{position:absolute;inset:0;background:rgb(6,6,8);opacity:.58;z-index:1}
body[data-state=paused] .vr .dimmer{opacity:.14}
.vr .screen{position:absolute;left:304px;top:221px;width:992px;height:558px;overflow:hidden;border-radius:4px;z-index:2}
.vr .credits{position:absolute;inset:0;background:#0b0b0c}
.vr .credits i{position:absolute;display:block;height:8px;border-radius:4px;background:rgba(220,220,220,.34)}
.vr .subt{position:absolute;left:304px;width:992px;text-align:center;font-size:32px;line-height:1.3;font-weight:500;color:#F3F3F1;
  text-shadow:0 0 4px rgba(0,0,0,.95),0 0 12px rgba(0,0,0,.6);z-index:3}
.vp{position:absolute;background:rgba(18,19,23,.92);border-radius:24px;color:var(--text);z-index:5}
.vp .ic{width:26px;height:26px}
.vround{position:absolute;border-radius:50%;display:grid;place-items:center;z-index:6}
.vround.pl{width:64px;height:64px;background:var(--text);color:#0E0F12}
.vround.pl .ic{width:30px;height:30px}
.vround.pl.hov{background:#fff;transform:scale(1.06)}
.vround.ex{width:56px;height:56px;background:rgba(18,19,23,.92);color:var(--text)}
.vt{position:absolute;font-size:22px;line-height:30px;font-weight:500;white-space:nowrap;z-index:6}
.vtrack{position:absolute;left:508px;width:544px;top:860px;height:6px;border-radius:3px;background:rgba(255,255,255,.22);z-index:6}
.vtrack .buf{position:absolute;left:0;top:0;bottom:0;width:52%;border-radius:3px;background:rgba(255,255,255,.34)}
.vtrack .played{position:absolute;left:0;top:0;bottom:0;width:37.8%;border-radius:3px;background:var(--accent)}
.vtrack.hov{top:858px;height:10px;border-radius:5px}
.vtrack .tick{position:absolute;top:-8px;width:4px;height:26px;margin-left:-2px;border-radius:2px;background:#fff}
.vbubble{position:absolute;padding:0 14px;height:40px;line-height:40px;border-radius:12px;background:var(--text);color:#0E0F12;
  font-size:22px;font-weight:600;z-index:7}
.vpill{position:absolute;height:56px;padding:0 18px;border-radius:28px;display:flex;align-items:center;gap:8px;
  font-size:22px;font-weight:600;background:rgba(255,255,255,.10);z-index:6}
.vpill .ic{width:24px;height:24px}
.vpill.on{background:rgba(255,255,255,.22)}
.vtabs{position:absolute;display:flex;gap:6px;z-index:6}
.vtabs span,.vseg span{height:50px;padding:0 14px;min-width:60px;border-radius:12px;display:inline-flex;align-items:center;justify-content:center;
  font-size:22px;font-weight:500;color:var(--text-2);white-space:nowrap}
.vtabs span.sel,.vseg span.sel{background:rgba(255,255,255,.22);color:var(--text);font-weight:600}
.vseg span.hov{background:rgba(255,255,255,.14);color:var(--text);transform:scale(1.04)}
.vseg{position:absolute;display:flex;gap:4px;z-index:6}
.vseg span{min-width:72px}
.vclose{position:absolute;width:50px;height:50px;border-radius:50%;display:grid;place-items:center;background:rgba(255,255,255,.10);z-index:6}
.vclose .ic{width:24px;height:24px}
.vbtn2{height:52px;padding:0 22px;border-radius:14px;display:inline-flex;align-items:center;gap:8px;font-size:22px;font-weight:600;
  background:rgba(255,255,255,.12);white-space:nowrap}
.vbtn2.primary{background:var(--text);color:#0E0F12}
.vbtn2.hov{transform:scale(1.04)}
.vbtn2.primary.hov{background:#fff}
.vbtn2 .ic{width:22px;height:22px}
.ring{width:26px;height:26px;border-radius:50%;background:conic-gradient(var(--text) 0 72%,rgba(255,255,255,.2) 72% 100%);
  -webkit-mask:radial-gradient(circle,transparent 8px,#000 9px);mask:radial-gradient(circle,transparent 8px,#000 9px);flex:none}
.steps{display:flex;gap:6px}
.steps i{display:block;width:22px;height:8px;border-radius:4px;background:rgba(255,255,255,.22)}
.steps i.on{background:var(--accent)}
.ray{position:absolute;inset:0;z-index:20;pointer-events:none}
.vcur{position:absolute;width:18px;height:18px;margin:-9px 0 0 -9px;border-radius:50%;background:#fff;
  box-shadow:0 0 0 3px rgba(0,0,0,.45),0 0 10px rgba(255,255,255,.35);z-index:21}
.ctrl{position:absolute;left:1262px;top:936px;width:96px;height:120px;border-radius:44px 44px 30px 30px;background:#26282c;
  box-shadow:inset 0 0 0 3px #34373c;transform:rotate(-24deg);z-index:19}
.ctrl::after{content:"";position:absolute;left:24px;top:14px;width:48px;height:48px;border-radius:50%;box-shadow:inset 0 0 0 5px #3d4046}
.mz{position:absolute;z-index:40;font:500 14px/1.35 "SF Mono",Menlo,monospace;color:#0c1a20;background:#7FD7FF;border-radius:6px;padding:3px 8px;white-space:nowrap}
.mzb{position:absolute;z-index:39;border:2px dashed #7FD7FF;border-radius:6px}
.mzl{position:absolute;z-index:39;background:#7FD7FF}
.anno.layers{right:20px;top:60px;white-space:nowrap}
.anno.ruler{left:20px;bottom:60px;display:flex;align-items:center;gap:10px}
.anno.ruler i{display:block;width:200px;height:10px;border:2px solid #f2f2f2;border-top:0}
"""

TOTAL = '1:52:00'


def ray(x, y, x0=1306, y0=1000):
    return (f'<svg class="ray" width="1600" height="1000"><defs><linearGradient id="rg" gradientUnits="userSpaceOnUse" '
            f'x1="{x0}" y1="{y0}" x2="{x}" y2="{y}"><stop offset="0" stop-color="#fff" stop-opacity=".7"/>'
            f'<stop offset="1" stop-color="#fff" stop-opacity=".05"/></linearGradient></defs>'
            f'<path d="M{x0 - 5} {y0} L{x - 1} {y} L{x + 1} {y} L{x0 + 5} {y0} Z" fill="url(#rg)"/></svg>'
            f'<div class="vcur" style="left:{x}px;top:{y}px"></div><div class="ctrl"></div>')


def bar(play_icon='pause', hover_track=False, adjust_on=False, play_hover=False, dm=None, src='NAS', dm_hover=False):
    """dm: None = the source has no danmaku, so the button is not drawn (and the track keeps its full
    27.2 deg); 'on' / 'off' = danmaku button state. With the button the track shortens to 23.2 deg."""
    tstyle = ' style="width:464px"' if dm else ''
    track = ('<div class="vtrack' + (' hov' if hover_track else '') + f'"{tstyle}><div class="buf"></div><div class="played"></div>'
             + ('<div class="tick" style="left:58.2%"></div>' if hover_track else '') + '</div>')
    bubble = '<div class="vbubble" style="left:825px;top:806px;transform:translateX(-50%)">1:05:10</div>' if hover_track else ''
    title = ('<div class="vt" style="left:508px;top:818px"><b style="font-weight:600">雾港</b>'
             f'<span class="c2"> · 2025 · 来自 {src}</span></div>') if not hover_track else ''
    rtime = 872 if dm else 952
    dmb = ''
    if dm:
        dmb = (f'<div class="vround dmb {dm}{" hov" if dm_hover else ""}" style="left:996px;top:834px"><span>弹</span>'
               + ('<i></i>' if dm == 'off' else '') + '</div>')
    return ('<div class="vround ex" style="left:330px;top:834px">' + ic('chev-l') + '</div>'
            '<div class="vp" style="left:400px;top:806px;width:800px;height:112px"></div>'
            f'<div class="vround pl{" hov" if play_hover else ""}" style="left:420px;top:830px">{ic(play_icon)}</div>'
            + title + track + bubble +
            '<div class="vt c2" style="left:508px;top:874px">0:42:18</div>'
            f'<div class="vt c2" style="left:{rtime}px;top:874px;width:100px;text-align:right">-1:09:42</div>'
            + dmb +
            f'<div class="vpill{" on" if adjust_on else ""}" style="left:1076px;top:834px">{ic("sliders")}调整</div>')


def menu():
    tabs = ''.join(f'<span class="{"sel" if t == "3D" else ""}">{t}</span>' for t in ('3D', '屏幕', '字幕', '音轨', '环境'))
    s1 = ''.join(f'<span class="{"sel" if o == "标准" else ""}{"hov" if o == "强烈" else ""}">{o}</span>'
                 for o in ('关闭', '柔和', '标准', '强烈'))
    s2 = ''.join(f'<span class="{"sel" if o == "自动转换" else ""}">{o}</span>' for o in ('自动转换', '左右 3D', '上下 3D'))
    return ('<div class="vp" style="left:680px;top:522px;width:520px;height:268px"></div>'
            f'<div class="vtabs" style="left:700px;top:542px">{tabs}</div>'
            f'<div class="vclose" style="left:1130px;top:542px">{ic("close")}</div>'
            '<div class="vt c2" style="left:704px;top:620px">强度</div>'
            f'<div class="vseg" style="right:420px;top:610px">{s1}</div>'
            '<div class="vt c2" style="left:704px;top:682px">片源</div>'
            f'<div class="vseg" style="right:420px;top:672px">{s2}</div>'
            '<div class="vt c2" style="left:704px;top:740px">只对《雾港》生效 · 默认值在设置里</div>')


def screen(credits=False):
    if credits:
        lines = ''.join(f'<i style="left:{396 - w // 2}px;top:{t}px;width:{w}px"></i>' for t, w in
                        ((150, 120), (176, 200), (230, 90), (256, 170), (310, 140), (336, 220), (390, 110), (416, 180)))
        lines += ''.join(f'<i style="left:{596 - w // 2}px;top:{t}px;width:{w}px"></i>' for t, w in
                         ((150, 160), (176, 120), (230, 190), (256, 100), (310, 150), (336, 130), (390, 170), (416, 90)))
        return f'<div class="screen"><div class="credits">{lines}</div></div>'
    return f'<div class="screen">{scene("harbor")}</div>'


def subt(top=716):
    return f'<div class="subt" style="top:{top}px">雾再大，灯也得亮着。</div>'


def next_card():
    return ('<div class="vp" style="left:680px;top:576px;width:520px;height:214px"></div>'
            '<div class="abs" style="left:700px;top:596px;width:176px;height:99px;border-radius:12px;overflow:hidden;z-index:6">'
            + scene('tide', 'filter:hue-rotate(-30deg) saturate(.7) brightness(1.05)') + '</div>'
            '<div class="vt c2" style="left:896px;top:594px">下一集</div>'
            '<div class="vt" style="left:896px;top:624px;font-size:28px;line-height:38px;font-weight:600">第 5 集 · 迷雾</div>'
            '<div class="vt c2" style="left:896px;top:666px;display:flex;align-items:center;gap:10px"><span class="ring"></span>8 秒后播放</div>'
            '<div class="abs" style="left:700px;top:718px;display:flex;gap:12px;z-index:6">'
            f'<span class="vbtn2 primary hov">{ic("play")}立即播放</span><span class="vbtn2">取消</span></div>')


def cast_card():
    return ('<div class="vp" style="left:680px;top:600px;width:520px;height:190px"></div>'
            f'<div class="abs c2" style="left:704px;top:624px;z-index:6">{ic("phone", 36)}</div>'
            '<div class="vt" style="left:756px;top:618px;font-size:26px;line-height:36px;font-weight:600">小林的 iPhone 想投屏到这里</div>'
            '<div class="vt c2" style="left:756px;top:656px">《雾港》会暂停，并记住看到哪里</div>'
            '<div class="abs" style="left:756px;top:718px;display:flex;gap:12px;z-index:6">'
            '<span class="vbtn2 primary hov">切换过去</span><span class="vbtn2">忽略</span></div>')


def toast():
    return ('<div class="vp" style="left:680px;top:834px;width:240px;height:52px;border-radius:26px;display:flex;align-items:center;'
            'justify-content:center;gap:14px;font-size:24px;font-weight:500"><span class="c2">弹幕</span>'
            '<b style="font-weight:600">已关</b></div>')


def buffering():
    return ('<div class="vp" style="left:690px;top:468px;width:220px;height:64px;border-radius:32px;display:flex;align-items:center;'
            f'justify-content:center;gap:12px;font-size:24px;font-weight:500">{ic("spinner", 26, "spin")}正在缓冲</div>')


def measure():
    m = ''
    box = lambda x, y, w, h: f'<div class="mzb" style="left:{x}px;top:{y}px;width:{w}px;height:{h}px"></div>'
    lab = lambda x, y, t: f'<div class="mz" style="left:{x}px;top:{y}px">{t}</div>'
    m += box(302, 219, 996, 562) + lab(312, 229, '画面 · 默认「中」≈ 50° 宽 · 下沿俯角 14°')
    m += box(398, 804, 804, 116) + lab(40, 742, '控制条 40° × 5.6° · 距眼 1.4 m') + lab(40, 770, '俯角 15°–21°，限制在 10°–30°')
    m += box(678, 520, 524, 272) + lab(1210, 524, '快捷菜单 26° × 13.4°')
    m += lab(1210, 552, '从「调整」正上方弹出，右缘对齐')
    m += lab(1210, 612, '选项 50 px = 2.5°（最小目标）')
    m += lab(1210, 744, '最小字 22 px = 1.1°')
    m += f'<div class="mzl" style="left:248px;top:862px;width:72px;height:2px"></div>' + lab(130, 850, '退出 2.8°')
    m += lab(1210, 852, '播放 64 px = 3.2°')
    m += lab(1210, 880, '进度条 0.3° 粗 · 命中区 2.5° 高')
    m += f'<div class="mzl" style="left:1300px;top:780px;width:2px;height:26px"></div>' + lab(1310, 782, '间隔 1.4°')
    return m


def vr_page():
    room = ROOM + '<div class="vr"><div class="dimmer"></div>'
    S = {}
    S['menu'] = screen() + subt(474) + bar(adjust_on=True) + menu() + ray(1166, 652)
    S['bar'] = screen() + subt() + bar(hover_track=True) + ray(825, 863)
    S['paused'] = screen() + bar(play_icon='play', play_hover=True) + ray(474, 882)
    S['toast'] = screen() + subt() + toast()
    S['next'] = screen(credits=True) + next_card() + ray(850, 760)
    S['cast'] = screen() + subt(474) + cast_card() + ray(874, 760)
    S['buffer'] = screen() + buffering()
    S['clean'] = screen() + subt()
    S['measure'] = screen() + subt(474) + bar(adjust_on=True) + menu() + measure()
    layers = {
        'menu': '9 / 16：调暗 1 · 画面 2 · 字幕 1 · 控制条 1 · 弹出卡片 1 · 射线 2 · 手柄 1（片源没有弹幕；有弹幕 +2）',
        'bar': '8 / 16：调暗 1 · 画面 2 · 字幕 1 · 控制条 1 · 射线 2 · 手柄 1（片源没有弹幕；有弹幕 +2）',
        'paused': '7 / 16：调暗 1 · 画面 2 · 控制条 1 · 射线 2 · 手柄 1（片源没有弹幕；有弹幕 +2）',
        'toast': '5 / 16：调暗 1 · 画面 2 · 字幕 1 · 提示 1（片源没有弹幕；有弹幕 +2）',
        'next': '7 / 16：调暗 1 · 画面 2 · 弹出卡片 1 · 射线 2 · 手柄 1（片源没有弹幕；有弹幕 +2）',
        'cast': '8 / 16：调暗 1 · 画面 2 · 字幕 1 · 弹出卡片 1 · 射线 2 · 手柄 1（片源没有弹幕；有弹幕 +2）',
        'buffer': '4 / 16：调暗 1 · 画面 2 · 弹出卡片 1（片源没有弹幕；有弹幕 +2）',
        'clean': '4 / 16：调暗 1 · 画面 2 · 字幕 1（片源没有弹幕；有弹幕 +2）',
        'measure': '9 / 16 · 上限设计值 13（含弹幕、调色板和发光层，见 §6.5）',
    }
    states = [
        ('menu', '快捷菜单（已被取代）', '<b>这一版已被手上的调色板取代，见 vr-palette.html 和 ux-spec §6.3。</b>旧方案：点「调整」打开，菜单从控制条右端向上长出来，字幕临时抬到菜单上方。'),
        ('bar', '控制条', '射线进入画面或控制条时出现，离开 3 秒后淡出。悬停进度条：线变粗、出现时间气泡，扳机跳转。'),
        ('paused', '暂停', '暂停时房间亮回来（「开灯」），控制条常驻不隐藏。'),
        ('toast', '提示', '按 Y 开关弹幕、摇杆快进快退：不弹界面，只在视线下方 18° 出一条提示，2.5 秒后消失。（原来的「摇杆上下改 3D 强度」已去掉）'),
        ('next', '下一集', '片尾字幕开始时，在控制条上方（原来菜单的位置）出现下一集卡片，倒数 8 秒。不遮画面中央。'),
        ('cast', '投屏请求', '看片时有人投屏：同一个位置出卡片，字幕抬到上方，不自动打断。'),
        ('buffer', '缓冲', '超过 500 ms 才出现，放在画面中央，只有一行字。'),
        ('clean', '沉浸', '什么都不碰时：只剩画面和字幕，手柄和射线也隐藏。'),
        ('measure', '尺寸标注', '1° = 20 px。尺寸都按视角给，贴图按 40 texel/° 生成。'),
    ]
    body = room
    for sid, html in S.items():
        html = html.replace('id="rg"', f'id="rg-{sid}"').replace('url(#rg)', f'url(#rg-{sid})')
        body += f'<div data-st="{sid}">{html}</div>'
    body += '</div>'
    links = ''.join(f'<a href="#{sid}">{label}</a>' for sid, label, _ in states)
    body += ('<div class="anno t"><b>VR 播放中</b> <span>· 头显视野 80° × 50°，1° = 20 px · 透视模式</span></div>'
             '<a class="anno home" href="index.html">总览</a>'
             + ''.join(f'<div class="anno layers" data-st="{k}">图层 {v}</div>' for k, v in layers.items()) +
             '<div class="anno ruler"><i></i>10°</div>'
             f'<div class="anno st">状态：{links}</div>'
             + ''.join(f'<div class="anno note" data-st="{sid}">{n}</div>' for sid, _, n in states))
    return page('depth3d · VR 播放', body, 'menu', VR_CSS)

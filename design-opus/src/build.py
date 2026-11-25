"""Writes every mockup in ../mockups. Each page comes out as one self-contained
HTML file (CSS, icons and the state switcher inlined, nothing loaded from the
network), so edit here and rebuild rather than editing the HTML by hand.

    python3 design-opus/src/build.py
"""
import os, sys
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from lib import BASE_CSS
import pages, vr, palette, search

OUT = os.path.normpath(os.path.join(HERE, '..', 'mockups'))
os.makedirs(OUT, exist_ok=True)

PAGES = [
    ('home', '首页播放墙', pages.home, [('default', '默认'), ('scrolled', '向下滚动'), ('empty', '没有播放源'), ('loading', '加载中'), ('error', '一个源连不上')]),
    ('library', '媒体库', pages.library, [('default', '播放源与分类'), ('grid', '分类网格'), ('offline', '源连不上')]),
    ('movie', '电影详情', pages.movie, [('default', '默认'), ('picker', '3D 设置'), ('loading', '加载中'), ('error', '不能播放')]),
    ('series', '剧集详情', pages.series, [('default', '选季选集'), ('loading', '切换季')]),
    ('sources', '播放源与插件', pages.sources, [('default', '播放源'), ('plugins', '插件'), ('add', '添加'), ('phone', '用手机输入')]),
    ('cast', '投屏接收', pages.cast, [('default', '等待投屏'), ('confirm', '首次确认'), ('connecting', '连接中'), ('pin', '电脑配对'), ('nowifi', '没有网络')]),
    ('onboarding', '首次使用引导', pages.onboarding, [('welcome', '欢迎'), ('sources', '片源'), ('mac', '装 Host'), ('controls', '操作')]),
    ('search', '搜索', search.search_page, [(s, l) for s, l, _ in search.STATES]),
    ('settings', '设置', pages.settings, [('default', '播放与 3D'), ('screen', '屏幕与环境'), ('about', '关于')]),
    ('vr-player', 'VR 播放中', vr.vr_page, [('menu', '快捷菜单（旧）'), ('bar', '控制条'), ('paused', '暂停'), ('toast', '提示'), ('next', '下一集'), ('cast', '投屏请求'), ('buffer', '缓冲'), ('clean', '沉浸'), ('measure', '尺寸标注')]),
    ('vr-palette', 'VR 调色板与弹幕', palette.palette_page, [(s, l) for s, l, _ in palette.STATES]),
]

for name, _, fn, _ in PAGES:
    open(os.path.join(OUT, name + '.html'), 'w', encoding='utf-8').write(fn())

INDEX_CSS = """
html,body{overflow:auto;height:auto;background:#0E0F12}
.wrap{width:1520px;margin:0 auto;padding:48px 0 80px}
h1{font-size:40px;line-height:1.2;font-weight:600}
.lede{margin-top:12px;font-size:20px;line-height:1.6;color:var(--text-2);max-width:1100px}
.facts{margin-top:24px;display:flex;gap:12px;flex-wrap:wrap}
.facts span{font-size:18px;font-weight:500;padding:8px 14px;border-radius:10px;background:var(--surface);color:var(--text-2)}
.facts b{color:var(--text);font-weight:600}
.grid{margin-top:40px;display:grid;grid-template-columns:repeat(3,480px);gap:40px}
.it a.th{display:block;position:relative;width:480px;height:300px;border-radius:12px;overflow:hidden;background:#000}
.it iframe{position:absolute;left:0;top:0;width:1600px;height:1000px;border:0;transform:scale(.3);transform-origin:0 0;pointer-events:none}
.it h2{margin-top:16px;font-size:24px;font-weight:600}
.it h2 small{font-size:16px;color:var(--text-3);font-weight:500;margin-left:8px;font-family:"SF Mono",Menlo,monospace}
.it .sts{margin-top:8px;display:flex;flex-wrap:wrap;gap:6px}
.it .sts a{font-size:16px;color:var(--text-2);text-decoration:none;padding:4px 10px;border-radius:8px;background:var(--surface)}
.it .sts a:hover{color:var(--text);background:var(--surface-hi)}
"""
items = ''
for name, title, _, sts in PAGES:
    links = ''.join(f'<a href="{name}.html#{s}">{l}</a>' for s, l in sts)
    items += (f'<div class="it"><a class="th" href="{name}.html"><iframe src="{name}.html" loading="eager" tabindex="-1"></iframe></a>'
              f'<h2>{title}<small>{name}.html</small></h2><div class="sts">{links}</div></div>')
index = ('<!doctype html><html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">'
         f'<title>depth3d 头显界面</title><style>{BASE_CSS}{INDEX_CSS}</style></head><body><div class="wrap">'
         '<h1>depth3d 头显界面 · 设计稿</h1>'
         '<p class="lede">2D 面板是 1280 × 720 的窗口，画在一个模拟的透视房间里；VR 播放页是头显视野本身。每页底部可以切换状态，右下角写着这个状态为什么这样做。完整的理由见 ux-spec.md。</p>'
         '<div class="facts"><span>面板 1° ≈ <b>24 px</b></span><span>最小字 <b>24 px</b> · 正文 <b>28 px</b></span>'
         '<span>最小目标 <b>60 px</b> · 主要目标 <b>64 px</b></span><span>线条至少 <b>2 px</b></span>'
         '<span>播放中图层 <b>≤ 13 / 16</b></span><span>调色板 <b>20 × 15 cm</b> · 贴图 <b>1024 × 768</b></span><span>弹幕 <b>1.4°</b> · 字幕 <b>1.6°</b></span><span>强调色只用于进度和「已开启」</span></div>'
         f'<div class="grid">{items}</div></div></body></html>')
open(os.path.join(OUT, 'index.html'), 'w', encoding='utf-8').write(index)
print('ok', sorted(os.listdir(OUT)))

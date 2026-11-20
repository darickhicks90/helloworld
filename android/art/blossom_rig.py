import math
from PIL import Image, ImageDraw, ImageFilter, ImageEnhance
base=Image.open('cut.png').convert('RGBA'); W,H=base.size; bp=base.load()
def lum(c): return 0.3*c[0]+0.59*c[1]+0.11*c[2]
# ---- the dagger, drawn at this scale (blade down over the hip, grip behind the fingers)
dag=Image.new('RGBA',(W,H),(0,0,0,0)); d=dag.load()
def put(x,y,c):
    x=int(round(x)); y=int(round(y))
    if 0<=x<W and 0<=y<H: d[x,y]=c
gx0,gy0=158,312; gx1,gy1=150,488     # pommel ... tip
ang=math.atan2(gy1-gy0,gx1-gx0); ux,uy=math.cos(ang),math.sin(ang); nx,ny=-uy,ux
L=math.hypot(gx1-gx0,gy1-gy0)
INK=(32,24,40,255)
for i in range(int(L*2)+1):
    t=i/2; cx=gx0+ux*t; cy=gy0+uy*t
    if t<10:      # a rosebud pommel
        r=6.5-abs(t-5)*0.6
        for k in range(-7,8):
            if abs(k)<=r: put(cx+nx*k,cy+ny*k,(232,120,160,255) if k<0 else (196,80,124,255))
            elif abs(k)<=r+1: put(cx+nx*k,cy+ny*k,INK)
    elif t<50:    # the grip, wound with green vine
        for k in range(-5,6):
            if abs(k)==5: put(cx+nx*k,cy+ny*k,INK)
            else: put(cx+nx*k,cy+ny*k,(74,122,58,255) if (int(t)+k)%7<3 else (110,70,40,255))
    elif t<58:    # the guard: gold, curling into two leaves
        for k in range(-17,18):
            c=(240,204,80,255) if t<54 else (168,132,32,255)
            if abs(k)>=15: c=(88,168,72,255)
            put(cx+nx*k,cy+ny*k,c)
        put(cx+nx*-18,cy+ny*-18,INK); put(cx+nx*18,cy+ny*18,INK)
    else:         # the blade, tapering to the point, with a fuller and a bright edge
        f=(t-58)/(L-58); half=7*(1-f**1.6)+0.6
        for k in range(-8,9):
            if abs(k)>half+1: continue
            if abs(k)>half: put(cx+nx*k,cy+ny*k,INK); continue
            c=(224,230,240,255) if k<0 else (150,160,178,255)
            if abs(k)<0.8 and f<0.75: c=(120,128,146,255)
            if k<=-half+1: c=(250,252,255,255)
            put(cx+nx*k,cy+ny*k,c)
comp=base.copy(); comp.alpha_composite(dag)
# her fingers back over the grip
cp=comp.load()
for y in range(318,366):
    for x in range(136,172):
        c=bp[x,y]
        if c[3] and lum(c)<175 and abs(x-154)<14: cp[x,y]=c
comp.save('her_rig.png')
# ---- the duel pose: about 300 px tall, a touch of sharpening
dh=300; dw=round(W*dh/H)
duel=comp.resize((dw,dh),Image.LANCZOS)
duel=duel.filter(ImageFilter.UnsharpMask(radius=1,percent=60,threshold=2))
a=duel.split()[3].point(lambda v: 0 if v<90 else 255); duel.putalpha(a)
duel.save('duel_blossom.png'); print('duel',duel.size)
# where her raised (blessing) hand is, as fractions of the picture
hx,hy=345/W,30/H; print('hand frac',round(hx,3),round(hy,3))
# ---- the little sprite for the hall: 72 x 72 cells, six frames, feet on the bottom row
sh=58; sw=max(1,round(W*sh/H))
small=comp.resize((sw,sh),Image.LANCZOS)
sa=small.split()[3].point(lambda v: 0 if v<110 else 255); small.putalpha(sa)
# volume for the white hall: cooler and darker toward her right and her feet, the fur strokes kept
sp=small.load()
cols=[x for x in range(sw) for y in range(sh) if sp[x,y][3]]
for y in range(sh):
    row=[x for x in range(sw) if sp[x,y][3]]
    if not row: continue
    l0,r0=min(row),max(row)
    for x in row:
        r,g,b,a=sp[x,y]
        t=(x-l0)/max(1,r0-l0)
        k=0.96+0.05*(1-t)-0.03*(y/sh)
        edge=(x==l0 or x==r0)
        if max(r,g,b)-min(r,g,b)<40:   # her white fur (not the eyes, lips, flower or dagger): lifted toward white
            lift=lambda v: int(255-(255-v)*0.5)
            r,g,b=lift(r),lift(g),lift(b)
        r,g,b=min(255,int(r*k)),min(255,int(g*k)),min(255,int(b*k+3))
        if edge: r,g,b=int(r*0.86),int(g*0.86),int(b*0.9)
        sp[x,y]=(r,g,b,255)
# her features, carried down from the big drawing so they read at this size
feat=comp.load()
def stamp(fx,fy,col,size=1):
    x=round(fx*sw/W); y=round(fy*sh/H)
    for dx in range(size):
        for dy in range(size):
            if 0<=x+dx<sw and 0<=y+dy<sh and sp[x+dx,y+dy][3]: sp[x+dx,y+dy]=col
BLUE=(56,120,200,255); PINK=(232,112,150,255); INKD=(40,32,48,255)
stamp(172,72,(250,250,255,255),2); stamp(173,74,BLUE,1); stamp(171,72,INKD,1)      # the great eye
for (ex,ey) in ((124,121),(219,121),(133,145),(212,145)): stamp(ex,ey,BLUE)        # the four
stamp(170,212,PINK); stamp(173,212,PINK)                                              # lips
stamp(118,68,PINK,2)                                                                   # the flower
small.putalpha(small.split()[3])
def outline(im):
    p=im.load(); w,h=im.size; q=im.copy().load()
    for y in range(h):
        for x in range(w):
            if q[x,y][3]==0 and any(0<=x+dx<w and 0<=y+dy<h and q[x+dx,y+dy][3]>0 for dx,dy in((1,0),(-1,0),(0,1),(0,-1))): p[x,y]=(48,40,56,255)
    return im
pad=Image.new('RGBA',(sw+6,sh+2),(0,0,0,0)); pad.alpha_composite(small,(3,1)); small=outline(pad)
print('hall sprite',small.size)
def cell(img, dx=0, dy=0, rot=0, glow=False, blink=False):
    c=Image.new('RGBA',(72,72),(0,0,0,0))
    im=img.copy()
    if blink:
        p=im.load()
        for y in range(im.height):
            for x in range(im.width):
                r,g,b,a=p[x,y]
                if a and b>r+30: p[x,y]=(232,228,224,255)
    if rot: im=im.rotate(rot,resample=Image.NEAREST,expand=True,center=(im.width/2,im.height))
    x=36-im.width//2+dx; y=72-im.height+dy
    c.alpha_composite(im,(max(0,x),max(0,y)))
    if glow:
        # the blessing: gold light round the raised hand
        hxp=x+round(hx*(sw))+3; hyp=y+round(hy*sh)+1
        g=Image.new('RGBA',(72,72),(0,0,0,0)); gd=ImageDraw.Draw(g)
        for r_,a_ in ((9,60),(6,110),(3,200)): gd.ellipse((hxp-r_,hyp-r_,hxp+r_,hyp+r_),fill=(255,236,150,a_))
        g.alpha_composite(c); c=g
        cc=c.load()
        for (ox,oy) in ((-6,-7),(7,-5),(-8,3),(6,6),(0,-10)):
            if 0<=hxp+ox<72 and 0<=hyp+oy<72: cc[hxp+ox,hyp+oy]=(255,255,255,255)
    return c
frames=[cell(small), cell(small,dy=1), cell(small,rot=-3), cell(small,glow=True), cell(small,blink=True), cell(small,rot=7,dx=2)]
sheet=Image.new('RGBA',(72*6,72),(0,0,0,0))
for i,f in enumerate(frames): sheet.alpha_composite(f,(i*72,0))
sheet.save('hall_blossom.png')
# preview
pv=Image.new('RGBA',(72*6*3+40+dw*2,max(216,dh*2)),(232,232,240,255))
pv.alpha_composite(sheet.resize((72*6*3,216),Image.NEAREST),(0,0)); pv.alpha_composite(duel.resize((dw*2,dh*2),Image.NEAREST),(72*6*3+40,0)); pv.save('her_preview.png')

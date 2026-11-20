from PIL import Image, ImageDraw, ImageFilter
comp=Image.open('her_rig2.png').convert('RGBA'); W,H=comp.size
sh=60; k=sh/H; sw=max(1,round(W*k))
small=comp.resize((sw,sh),Image.LANCZOS)
small.putalpha(small.split()[3].point(lambda v: 0 if v<110 else 255))
sp=small.load()
for y in range(sh):
    row=[x for x in range(sw) if sp[x,y][3]]
    if not row: continue
    l0,r0=min(row),max(row)
    for x in row:
        r,g,b,a=sp[x,y]; t=(x-l0)/max(1,r0-l0)
        kk=0.96+0.05*(1-t)-0.03*(y/sh)
        if max(r,g,b)-min(r,g,b)<40:
            lift=lambda v: int(255-(255-v)*0.5); r,g,b=lift(r),lift(g),lift(b)
        r,g,b=min(255,int(r*kk)),min(255,int(g*kk)),min(255,int(b*kk+3))
        if x==l0 or x==r0: r,g,b=int(r*0.86),int(g*0.86),int(b*0.9)
        sp[x,y]=(r,g,b,255)
def P(fx,fy): return round(fx*k), round(fy*k)
def put(x,y,c):
    if 0<=x<sw and 0<=y<sh and sp[x,y][3]: sp[x,y]=c
WHITE=(250,250,255,255); BLUE=(40,120,230,255); BLUE_L=(110,175,250,255); PUPIL=(24,30,60,255); LID=(110,100,120,255)
# the great eye: a white almond, a big blue iris, a dark pupil, a lid
ex,ey=P(172,74)
for dx in (-2,-1,0,1,2): put(ex+dx,ey,WHITE)
for dx in (-1,0,1): put(ex+dx,ey+1,WHITE)
put(ex-1,ey,BLUE); put(ex,ey,PUPIL); put(ex+1,ey,BLUE); put(ex,ey+1,BLUE)
for dx in (-2,-1,0,1,2): put(ex+dx,ey-1,LID)
put(ex-1,ey-1,BLUE_L)
# the four: two pixels of bright blue each, with a lid above
for (fx,fy) in ((124,121),(219,121),(133,147),(212,147)):
    x,y=P(fx,fy); put(x,y,BLUE); put(x+1,y,BLUE_L); put(x,y-1,LID); put(x+1,y-1,LID)
# her dagger at her hip: a gold guard and a steel blade
gx_,gy_=P(154,368)
for dx in (-1,0,1): put(gx_+dx,gy_,(232,196,80,255))
for i in range(1,6): put(gx_ - (1 if i>3 else 0), gy_+i, (214,222,236,255) if i<5 else (170,180,198,255))
put(gx_,gy_-1,(110,80,50,255)); put(gx_,gy_-2,(220,140,170,255))
# lips: a soft rose line, not a pink blot
lx,ly=P(171,213); put(lx-1,ly,(214,178,176,255)); put(lx,ly,(206,164,164,255)); put(lx+1,ly,(214,178,176,255))
small.putalpha(small.split()[3])
# room above her head for the flowers
PADT=7
canvas=Image.new('RGBA',(sw+6,sh+PADT+1),(0,0,0,0)); canvas.alpha_composite(small,(3,PADT))
cv=canvas.load()
def cp(x,y,c):
    if 0<=x<canvas.width and 0<=y<canvas.height: cv[x,y]=c
# the head's top edge, left of centre: two flowers on stems sprouting up from it (as on her model), sprigs of grass to the right
hx,hy=P(150,48); hx+=3; hy+=PADT
STEM=(110,130,96,255); PINK=(238,140,172,255); PINK_D=(206,100,140,255); YEL=(250,220,110,255); SPRIG=(150,160,140,255)
for i in range(1,6): cp(hx-(i//2),hy-i,STEM)
for i in range(1,4): cp(hx+1+(i//3),hy-i,STEM)
for (fx,fy) in ((hx-3,hy-6),(hx+2,hy-4)):
    for (dx,dy) in ((0,-1),(-1,0),(1,0),(0,1)): cp(fx+dx,fy+dy,PINK)
    cp(fx+1,fy+1,PINK_D); cp(fx,fy,YEL)
gx,gy=P(225,52); gx+=3; gy+=PADT
for (dx,dy) in ((0,-1),(1,-2),(-1,-2),(2,-3),(0,-3)): cp(gx+dx,gy+dy,SPRIG)
# a dark outline all round
q=canvas.copy().load()
for y in range(canvas.height):
    for x in range(canvas.width):
        if q[x,y][3]==0 and any(0<=x+dx<canvas.width and 0<=y+dy<canvas.height and q[x+dx,y+dy][3]>0 for dx,dy in((1,0),(-1,0),(0,1),(0,-1))): cv[x,y]=(48,40,56,255)
small=canvas; print('sprite',small.size)
hfx,hfy=345/W,30/H
def cell(img, dx=0, dy=0, rot=0, glow=False, blink=False):
    c=Image.new('RGBA',(72,72),(0,0,0,0)); im=img.copy()
    if blink:
        p=im.load()
        for y in range(im.height):
            for x in range(im.width):
                r,g,b,a=p[x,y]
                if a and b>r+40 and y<PADT+16: p[x,y]=(236,232,232,255)
    if rot: im=im.rotate(rot,resample=Image.NEAREST,expand=True,center=(im.width/2,im.height))
    x=36-im.width//2+dx; y=72-im.height+dy
    c.alpha_composite(im,(max(0,x),max(0,y)))
    if glow:
        hxp=x+3+round(hfx*sw); hyp=y+PADT+round(hfy*sh)
        g=Image.new('RGBA',(72,72),(0,0,0,0)); gd=ImageDraw.Draw(g)
        for r_,a_ in ((9,60),(6,110),(3,200)): gd.ellipse((hxp-r_,hyp-r_,hxp+r_,hyp+r_),fill=(255,236,150,a_))
        g.alpha_composite(c); c=g; cc=c.load()
        for (ox,oy) in ((-6,-7),(7,-5),(-8,3),(6,6),(0,-10)):
            if 0<=hxp+ox<72 and 0<=hyp+oy<72: cc[hxp+ox,hyp+oy]=(255,255,255,255)
    return c
frames=[cell(small), cell(small,dy=1), cell(small,rot=-3), cell(small,glow=True), cell(small,blink=True), cell(small,rot=7,dx=2)]
sheet=Image.new('RGBA',(72*6,72),(0,0,0,0))
for i,f in enumerate(frames): sheet.alpha_composite(f,(i*72,0))
sheet.save('hall_blossom2.png')
o=Image.new('RGBA',(72*4*3,72*4),(248,248,248,255))
y=Image.open('/home/user/helloworld/android/app/src/main/assets/images/hall_yeti_axe.png')
o.alpha_composite(y.crop((0,0,72,72)).resize((288,288),Image.NEAREST),(0,0)); o.alpha_composite(sheet.crop((0,0,72,72)).resize((288,288),Image.NEAREST),(288,0)); o.alpha_composite(sheet.crop((216,0,288,72)).resize((288,288),Image.NEAREST),(576,0)); o.save('hall_compare2.png')

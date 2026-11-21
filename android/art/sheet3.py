import numpy as np
from PIL import Image, ImageDraw
bless=Image.open('gbc_bless.png').convert('RGBA'); ready=Image.open('gbc_ready.png').convert('RGBA')
a=np.array(bless)[:,:,3]>0; half=bless.width//2; ys,xs=np.where(a[:,half:]); top=ys.min(); hand=(int(xs[ys==top].mean())+half, top+3)
def cell(img, dx=0, dy=0, rot=0, glow=None, blink=False, squash=0, glint=None):
    c=Image.new('RGBA',(72,72),(0,0,0,0)); im=img.copy()
    if blink:
        p=im.load()
        for y in range(im.height//4):
            for x in range(im.width):
                r,g,b,al=p[x,y]
                if al and b>r+40: p[x,y]=(242,230,220,255)
    if squash: im=im.resize((im.width+1,im.height-squash),Image.NEAREST)
    if rot: im=im.rotate(rot,resample=Image.NEAREST,expand=True,center=(im.width/2,im.height))
    x=36-im.width//2+dx; y=72-im.height+dy
    c.alpha_composite(im,(max(0,x),max(0,y)))
    if glow:
        hx,hy=x+glow[0],y+glow[1]
        g=Image.new('RGBA',(72,72),(0,0,0,0)); gd=ImageDraw.Draw(g)
        for r_,a_ in ((9,60),(6,110),(3,200)): gd.ellipse((hx-r_,hy-r_,hx+r_,hy+r_),fill=(255,236,150,a_))
        g.alpha_composite(c); c=g; cc=c.load()
        for (ox,oy) in ((-6,-7),(7,-5),(-8,3),(6,6),(0,-10)):
            if 0<=hx+ox<72 and 0<=hy+oy<72: cc[hx+ox,hy+oy]=(255,255,255,255)
    if glint:
        cc=c.load(); gx,gy=x+glint[0],y+glint[1]
        for (ox,oy) in ((0,0),(-1,0),(1,0),(0,-1),(0,1),(-2,0),(2,0)):
            if 0<=gx+ox<72 and 0<=gy+oy<72: cc[gx+ox,gy+oy]=(255,255,255,255)
    return c
# the dagger tip on the ready pose, for its glint
ra=np.array(ready); steel=(ra[:,:,0]==222)&(ra[:,:,1]==230)&(ra[:,:,2]==242)
sy,sx=np.where(steel); tip=(int(sx[sy.argmax()]),int(sy.max())) if len(sy) else (4,44)
frames=[cell(bless), cell(bless,dy=1), cell(bless,rot=-3), cell(bless,glow=hand), cell(bless,blink=True), cell(bless,rot=7,dx=2),
        # last stand: the blessing dropped, a quick restless idle (lean, dip, lean, a glint off the dagger)
        cell(ready), cell(ready,rot=5,dy=-1,dx=-1), cell(ready,squash=2), cell(ready,rot=-5,dy=-1,dx=1,glint=tip)]
sheet=Image.new('RGBA',(72*len(frames),72),(0,0,0,0))
for i,f in enumerate(frames): sheet.alpha_composite(f,(i*72,0))
sheet.save('hall_blossom3.png'); print(sheet.size, hand, tip)
sheet.resize((sheet.width*3,216),Image.NEAREST).save('sheet3_view.png')

import math
from PIL import Image
src=Image.open('duel_blossom.png').convert('RGBA').load()
im=Image.open('duel_blossom_fixed2.png').convert('RGBA'); px=im.load(); W,H=im.size
def lum(c): return 0.3*c[0]+0.59*c[1]+0.11*c[2]
body=sorted([src[x,y] for x in range(40,72) for y in range(140,175) if src[x,y][3]>200 and lum(src[x,y])>120], key=lum)
FUR=[tuple(int(c*0.8) for c in body[int(len(body)*0.02)][:3])]+[body[int(len(body)*k)][:3] for k in (0.05,0.35,0.7,0.97)]
OUT=(26,30,60,255); mean=sum(lum(c) for c in body)/len(body)
def shade(t):
    t=max(0,min(1,t)); f=t*(len(FUR)-1); i=int(f); u=f-i; a=FUR[i]; b=FUR[min(i+1,len(FUR)-1)]
    return tuple(int(a[k]+(b[k]-a[k])*u) for k in range(3))
def fur(x,y,light):
    c=shade(light); s=src[40+(x*7+y*3)%30, 140+(y*5+x)%33]; d=max(-9,min(9,(lum(s)-mean)*0.5))
    return (max(0,min(255,int(c[0]+d))),max(0,min(255,int(c[1]+d))),max(0,min(255,int(c[2]+d))),255)
def cap(x,y,a,b,r0,r1):
    vx=b[0]-a[0]; vy=b[1]-a[1]; ll=vx*vx+vy*vy
    t=max(0,min(1,((x-a[0])*vx+(y-a[1])*vy)/ll)); cx=a[0]+vx*t; cy=a[1]+vy*t; r=r0+(r1-r0)*t
    d=math.hypot(x-cx,y-cy)
    if d>r: return None
    # lit from the upper left
    nx=(x-cx)/r; ny=(y-cy)/r
    return 0.78-0.32*nx-0.2*ny
# 1. the raised arm, gone; her shoulder's outline redrawn
for y in range(0,90):
    edge = 89 if y<66 else 92 if y<78 else 95
    for x in range(edge,W): px[x,y]=(0,0,0,0)
for y in range(40,92):
    xs=[x for x in range(W) if px[x,y][3]>200]
    if not xs: continue
    r=max(xs)
    if lum(px[r,y])>90: px[r,y]=OUT
# 2. the arm: down at her side to the elbow, the forearm across in front of her chest, the hand raised at an angle
SH=(85,74); EL=(99,104); WR=(82,88)
def paint(mask, over_ok):
    for (x,y),v in mask.items(): px[x,y]=fur(x,y,v)
    for (x,y) in list(mask):
        for dx,dy in ((1,0),(-1,0),(0,1),(0,-1)):
            q=(x+dx,y+dy)
            if q in mask or not (0<=q[0]<W and 0<=q[1]<H): continue
            px[q[0],q[1]]=OUT if px[q[0],q[1]][3]==0 else (*shade(0.05),255)
upper={}
for y in range(60,116):
    for x in range(70,W):
        r=cap(x,y,SH,EL,6.0,5.0)
        if r is not None: upper[(x,y)]=r*0.92
fore={}
for y in range(60,116):
    for x in range(60,W):
        r=cap(x,y,EL,WR,4.6,3.6)
        if r is not None: fore[(x,y)]=r
for y in range(62,94):
    for x in range(62,92):
        e=((x-78)/4.4)**2+((y-82.5)/4.6)**2
        v=None
        if e<=1: v=0.86-0.3*((x-78)/4.4)-0.15*((y-82.5)/4.6)
        for (fx,fy,tx,ty) in ((75.2,79.5,72.6,73.0),(77.3,78.4,75.6,71.2),(79.4,78.6,78.8,71.6),(81.2,80.2,81.8,74.4),(82.4,84.4,85.8,81.0)):
            r=cap(x,y,(fx,fy),(tx,ty),1.4,1.15)
            if r is not None: v=r if v is None else max(v,r)
        if v is not None: fore[(x,y)]=v
paint(upper, True); paint(fore, True)
mask=dict(upper); mask.update(fore)
# leftovers of the old arm: stray outline beside her side, a speck up top
for y in range(0,124):
    for x in range(84,W):
        if (x,y) in mask: continue
        c=px[x,y]
        if c[3]==0: continue
        if y<22 or (lum(c)<110 and all(px[x-k,y][3]==0 for k in (1,2) if x-k>=0) and not any((x+dx,y+dy) in mask for dx in (-1,0,1) for dy in (-1,0,1))): px[x,y]=(0,0,0,0)
# the line between the hand and the wrist, finger gaps, a palm crease
for (x,y) in ((74,76),(76,75),(78,75),(80,76),(76,80),(79,81),(77,84)):
    if (x,y) in mask: px[x,y]=(*shade(0.25),255)
im.save('duel_blossom_fixed3.png')
bg=Image.new('RGBA',im.size,(220,220,235,255)); bg.alpha_composite(im); bg.resize((W*3,H*3),Image.NEAREST).save('pd_fixed3.png')
print('hand at', 78/W, 74/H)

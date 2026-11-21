import math, statistics
from PIL import Image
im=Image.open('duel_blossom.png').convert('RGBA'); W,H=im.size
px=im.load()
def lum(c): return 0.3*c[0]+0.59*c[1]+0.11*c[2]
# her own colours: fur (sorted light to dark), outline
body=[px[x,y] for x in range(40,72) for y in range(140,175) if px[x,y][3]>200 and lum(px[x,y])>120]
body.sort(key=lum)
FUR=[tuple(int(c*0.8) for c in body[int(len(body)*0.02)][:3])+(255,)]+[body[int(len(body)*k)] for k in (0.05,0.35,0.7,0.97)]
OUT=min((px[x,y] for x in range(W) for y in range(H) if px[x,y][3]>200), key=lum)
OUT=(OUT[0],OUT[1],OUT[2],255)
print('fur',FUR,'out',OUT)
mean=sum(lum(c) for c in body)/len(body)
def tex(x,y):
    # the fur's own grain, borrowed from her belly
    c=px0[40+(x*7+y*3)%30, 140+(y*5+x)%33]
    return (lum(c)-mean)
px0=Image.open('duel_blossom.png').convert('RGBA').load()
def shade(t):  # t: 0 dark .. 1 light
    t=max(0,min(1,t)); f=t*(len(FUR)-1); i=int(f); u=f-i
    a=FUR[i]; b=FUR[min(i+1,len(FUR)-1)]
    return tuple(int(a[k]+(b[k]-a[k])*u) for k in range(3))
def furpix(x,y,light):
    c=shade(light); d=max(-10,min(10,tex(x,y)*0.5))
    return (max(0,min(255,int(c[0]+d))),max(0,min(255,int(c[1]+d))),max(0,min(255,int(c[2]+d))),255)
def capsule(x,y,a,b,r0,r1):
    vx=b[0]-a[0]; vy=b[1]-a[1]; ll=vx*vx+vy*vy
    t=max(0,min(1,((x-a[0])*vx+(y-a[1])*vy)/ll)); cx=a[0]+vx*t; cy=a[1]+vy*t; r=r0+(r1-r0)*t
    d=math.hypot(x-cx,y-cy)
    if d>r: return None
    nx=(x-cx)/r
    return 0.75-0.45*nx  # lit from the left
def ell(x,y,cx,cy,rx,ry):
    d=((x-cx)/rx)**2+((y-cy)/ry)**2
    if d>1: return None
    return 0.78-0.4*((x-cx)/rx)-0.15*((y-cy)/ry)

# ---------------- the raised hand: an open palm, fingers spread (the arm itself stays as it was)
for y in range(0,15):
    for x in range(88,W): px[x,y]=(0,0,0,0)
mask={}
for y in range(0,20):
    for x in range(84,W):
        v=None
        r=ell(x,y,98.5,10.5,5.6,4.8)
        if r is not None: v=r
        for (fx,fy,tx,ty) in ((94.6,8,93.2,2),(97.2,7,96.8,0.5),(99.8,7,100.4,0.5),(102.3,8,103.8,2.5),(93.4,12,89.8,8.6)):
            rr=capsule(x,y,(fx,fy),(tx,ty),1.35,1.1)
            if rr is not None: v=rr if v is None else max(v,rr)
        if v is not None and y<15: mask[(x,y)]=v
for (x,y),v in mask.items(): px[x,y]=furpix(x,y,v)
for (x,y) in list(mask):
    for dx,dy in ((1,0),(-1,0),(0,1),(0,-1)):
        q=(x+dx,y+dy)
        if q in mask or not (0<=q[0]<W and 0<=q[1]<H): continue
        if px[q[0],q[1]][3]==0: px[q[0],q[1]]=OUT
for (x,y) in ((95,4),(95,5),(98,3),(98,4),(101,3),(101,4),(96,11),(97,12),(100,12)):
    if (x,y) in mask: px[x,y]=tuple(list(shade(0.25))+[255])
# ---------------- the hand at her hip, gripping the dagger
# clear the old dagger and the faint fingers
for y in range(118,172):
    for x in range(24,44):
        c=px[x,y]
        if c[3]==0: continue
        r,g,b,_=c
        steel = abs(r-g)<14 and abs(g-b)<18 and lum(c)<190
        gold = r>180 and g>140 and b<90
        green = g>r+20
        if steel or gold or green:
            # back to fur, from just right of it
            px[x,y]=furpix(x,y,0.62)
for y in range(122,140):
    for x in range(26,36):
        if px[x,y][3]>0: px[x,y]=furpix(x,y,0.6)
GX=37   # the dagger's line
# the dagger: pommel, grip (inside the fist), guard, a long blade down
STEEL=[(120,124,138),(176,180,194),(214,218,230),(244,246,252)]
GOLD=[(150,104,30),(220,170,60),(250,220,120)]
for y in range(135,166):
    w=1 if y>161 else 2
    for k in range(-w+1,w):
        px[GX+k,y]=tuple(list(STEEL[2 if k<0 else 1 if k==0 else 0])+[255])
    px[GX+w,y]=OUT; px[GX-w,y]=OUT
px[GX,166]=OUT
for x in range(GX-4,GX+5): px[x,134]=tuple(list(GOLD[1])+[255]); px[x,135]=tuple(list(GOLD[0])+[255])
px[GX,123]=tuple(list(GOLD[2])+[255]); px[GX+1,123]=tuple(list(GOLD[1])+[255])
# the fist round the grip: knuckles to us, thumb over the top
for y in range(122,136):
    for x in range(30,45):
        v=ell(x,y,37,129,4.6,5.2)
        if v is not None: px[x,y]=furpix(x,y,v)
for y in range(122,136):
    for x in range(29,46):
        if ell(x,y,37,129,4.6,5.2) is None and any(ell(x+dx,y+dy,37,129,4.6,5.2) is not None for dx,dy in ((1,0),(-1,0),(0,1),(0,-1))):
            if x>34: px[x,y]=(110,90,96,255)
for yy in (127,130,133):
    for x in range(35,41): px[x,yy]=tuple(list(shade(0.3))+[255])
for x in range(34,39): px[x,124]=tuple(list(shade(0.95))+[255])
im.save('duel_blossom_fixed.png')
bg=Image.new('RGBA',im.size,(220,220,235,255)); bg.alpha_composite(im); bg.resize((W*3,H*3),Image.NEAREST).save('pd_fixed.png')
print('ok')

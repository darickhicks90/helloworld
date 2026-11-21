import math, random
from PIL import Image
src=Image.open('duel_blossom.png').convert('RGBA').load()      # the untouched original, for fur samples
im=Image.open('duel_blossom_fixed.png').convert('RGBA'); px=im.load(); W,H=im.size
def lum(c): return 0.3*c[0]+0.59*c[1]+0.11*c[2]
body=sorted([src[x,y] for x in range(40,72) for y in range(140,175) if src[x,y][3]>200 and lum(src[x,y])>120], key=lum)
FUR=[tuple(int(c*0.8) for c in body[int(len(body)*0.02)][:3])]+[body[int(len(body)*k)][:3] for k in (0.05,0.35,0.7,0.97)]
OUT=(26,30,60,255)
mean=sum(lum(c) for c in body)/len(body)
rnd=random.Random(3)
def shade(t):
    t=max(0,min(1,t)); f=t*(len(FUR)-1); i=int(f); u=f-i
    a=FUR[i]; b=FUR[min(i+1,len(FUR)-1)]
    return tuple(int(a[k]+(b[k]-a[k])*u) for k in range(3))
def fur(x,y,light):
    c=shade(light); s=src[40+(x*7+y*3)%30, 140+(y*5+x)%33]; d=max(-9,min(9,(lum(s)-mean)*0.5))
    return (max(0,min(255,int(c[0]+d))),max(0,min(255,int(c[1]+d))),max(0,min(255,int(c[2]+d))),255)
def cap(x,y,a,b,r0,r1):
    vx=b[0]-a[0]; vy=b[1]-a[1]; ll=vx*vx+vy*vy
    t=max(0,min(1,((x-a[0])*vx+(y-a[1])*vy)/ll)); cx=a[0]+vx*t; cy=a[1]+vy*t; r=r0+(r1-r0)*t
    d=math.hypot(x-cx,y-cy)
    return None if d>r else 0.78-0.42*((x-cx)/r)

# ---------------- her left arm: hanging at her side, the dagger in her fist
# 1. take away the bent arm, the old fist and dagger; her side, filled back in with fur
for y in range(92,175):
    for x in range(0,20):
        if y<146: px[x,y]=(0,0,0,0)
for y in range(92,146):
    for x in range(20,30):
        if px[x,y][3]==0 or lum(px[x,y])<90: px[x,y]=fur(x,y,0.55+0.02*(x-20))
# fill where the old arm, fist and dagger lay over her hip by blending in from the fur all round, then the grain back
import numpy as np, cv2
arr=np.array(im)
mask=np.zeros((H,W),np.uint8)
for y in range(110,172):
    for x in range(21,47): mask[y,x]=255
base=arr[:,:,:3].copy()
fc=np.array(shade(0.6),np.uint8)
for yy in range(H):
    for xx in range(W):
        if arr[yy,xx,3]<200 or lum(tuple(int(v) for v in arr[yy,xx,:3]))<110: base[yy,xx]=fc
rgb=cv2.cvtColor(base,cv2.COLOR_RGB2BGR)
fill=cv2.inpaint(rgb,mask,5,cv2.INPAINT_TELEA)
fill=cv2.cvtColor(fill,cv2.COLOR_BGR2RGB)
for y in range(110,172):
    for x in range(21,47):
        if arr[y,x,3]==0 and x<24: continue
        sx=40+(x*7+y*3)%30; sy=140+(y*5+x)%33; g=src[sx,sy]; d=max(-8,min(8,(lum(g)-mean)*0.45))
        c=[int(v) for v in fill[y,x]]; px[x,y]=(int(max(0,min(255,c[0]+d))),int(max(0,min(255,c[1]+d))),int(max(0,min(255,c[2]+d))),255)
# her side's outline, as it would be without the arm in the way
for y in range(94,146): px[19,y]=OUT; px[20,y]=fur(20,y,0.45)
# 2. the arm, from the shoulder down, a little away from her side
SH=(23,101); EL=(14,124); WR=(16,146)
mask={}
for y in range(92,160):
    for x in range(4,34):
        v=None
        for r in (cap(x,y,SH,EL,6.2,5.0), cap(x,y,EL,WR,5.0,4.0)):
            if r is not None: v=r if v is None else max(v,r)
        e=((x-17)/4.4)**2+((y-151)/4.8)**2
        if e<=1: v=max(v or 0, 0.8-0.35*((x-17)/4.4)-0.12*((y-151)/4.8))
        if v is not None: mask[(x,y)]=v
for (x,y),v in mask.items(): px[x,y]=fur(x,y,v)
for (x,y) in list(mask):
    for dx,dy in ((1,0),(-1,0),(0,1),(0,-1)):
        q=(x+dx,y+dy)
        if q in mask or not (0<=q[0]<W and 0<=q[1]<H): continue
        if px[q[0],q[1]][3]==0: px[q[0],q[1]]=OUT
        elif q[0]>x: px[q[0],q[1]]=(*shade(0.12),255)     # a crease where the arm lies against her side
# knuckles round the grip
for yy in (149,152,155):
    for x in range(15,21):
        if (x,yy) in mask: px[x,yy]=(*shade(0.25),255)
# 3. the dagger: pommel above the fist, guard below it, the blade pointing down
GX=17
STEEL=[(120,124,138),(176,180,194),(214,218,230),(244,246,252)]; GOLD=[(150,104,30),(220,170,60),(250,220,120)]
px[GX,145]=(*GOLD[2],255); px[GX+1,145]=(*GOLD[1],255); px[GX,144]=OUT; px[GX+1,144]=OUT
for x in range(GX-4,GX+6): px[x,157]=(*GOLD[1],255); px[x,158]=(*GOLD[0],255)
px[GX-5,157]=OUT; px[GX+6,157]=OUT; px[GX-5,158]=OUT; px[GX+6,158]=OUT
for y in range(159,188):
    w=1 if y>183 else 2
    for k in range(-w+1,w): px[GX+k,y]=(*STEEL[2 if k<0 else 1 if k==0 else 0],255)
    px[GX+w,y]=OUT; px[GX-w,y]=OUT
px[GX,188]=OUT

# ---------------- her eyes, set into the face
SK=(*shade(0.85),255); SKD=(*shade(0.35),255)
LID=(70,48,62,255); WHITE=(250,248,242,255); IRIS=(74,146,232,255); IRISD=(38,84,176,255); PUP=(16,18,40,255); GLINT=(255,255,255,255)
def clear(l,t,r,b):
    for y in range(t,b+1):
        for x in range(l,r+1): px[x,y]=fur(x,y,0.82)
for (cx,cy) in ((39,55),(60,55),(39,62),(59,62)):
    clear(cx-3,cy-2,cx+3,cy+3)
    for x in range(cx-2,cx+3): px[x,cy-1]=LID
    px[cx-3,cy]=LID; px[cx+3,cy]=LID
    for x in range(cx-2,cx+3): px[x,cy]=WHITE; px[x,cy+1]=WHITE
    px[cx-1,cy]=IRIS; px[cx,cy]=PUP; px[cx-1,cy+1]=IRISD; px[cx,cy+1]=IRISD; px[cx+1,cy]=IRIS
    px[cx-1,cy]=GLINT
    for x in range(cx-2,cx+3): px[x,cy+2]=SKD
# the great eye on her brow: an almond, a slit pupil
clear(43,33,53,43)
for x in range(45,52): px[x,35]=LID
px[44,36]=LID; px[52,36]=LID; px[43,37]=LID; px[53,37]=LID
for y in range(36,41):
    for x in range(44,53):
        if (x-48)**2/20+(y-38)**2/5.5<=1: px[x,y]=WHITE
for y in range(36,41):
    for x in range(46,51):
        if (x-48)**2+(y-38)**2<=5: px[x,y]=IRIS if (x-48)**2+(y-38)**2<=2 else IRISD
for y in (37,38,39): px[48,y]=PUP
px[47,37]=GLINT
for x in range(45,52): px[x,41]=SKD
im.save('duel_blossom_fixed2.png')
bg=Image.new('RGBA',im.size,(220,220,235,255)); bg.alpha_composite(im); bg.resize((W*3,H*3),Image.NEAREST).save('pd_fixed2.png')
print('ok')

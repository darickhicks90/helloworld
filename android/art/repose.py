import math, sys
import numpy as np
from PIL import Image, ImageDraw
src=Image.open('model_front.png').convert('RGBA'); SW,SH=src.size; sa=np.array(src)
PL,PT,PR=70,110,70
W,H=SW+PL+PR, SH+PT
def P(x,y): return (x+PL, y+PT)
# ---- the arms as they hang (polygons), and the body without them
Lpoly=[(0,300),(40,300),(78,340),(90,480),(94,560),(88,700),(84,800),(0,800)]
Rpoly=[(SW,300),(440,300),(404,340),(394,480),(390,560),(398,700),(404,800),(SW,800)]
def polymask(poly):
    m=Image.new('L',(SW,SH),0); ImageDraw.Draw(m).polygon(poly,fill=255); return np.array(m)>0
Lm=polymask(Lpoly)&(sa[:,:,3]>0); Rm=polymask(Rpoly)&(sa[:,:,3]>0)
body=sa.copy(); body[Lm|Rm,3]=0
mode=sys.argv[1] if len(sys.argv)>1 else 'bless'
# ---- remap each arm's fur along a new bent path (shoulder, elbow, wrist, fingertips)
def grow(mask, S, F, path, half=38):
    out=np.zeros((H,W,4),np.uint8)
    S=np.array(S,float); F=np.array(F,float); ax=F-S; L=np.linalg.norm(ax); ux=ax/L; nx=np.array([-ux[1],ux[0]])
    segs=[]; tot=0
    for a,b in zip(path,path[1:]):
        a=np.array(a,float); b=np.array(b,float); l=np.linalg.norm(b-a); segs.append((a,b,l,tot)); tot+=l
    xs=[p[0] for p in path]; ys=[p[1] for p in path]
    for y in range(int(min(ys))-70,int(max(ys))+70):
        if y<0 or y>=H: continue
        for x in range(int(min(xs))-70,int(max(xs))+70):
            if x<0 or x>=W: continue
            best=None
            for a,b,l,off in segs:
                d=(b-a)/l; t=max(0,min(l,(x-a[0])*d[0]+(y-a[1])*d[1])); p=a+d*t
                dd=math.hypot(x-p[0],y-p[1])
                if best is None or dd<best[0]:
                    sd=(x-p[0])*(-d[1])+(y-p[1])*d[0]
                    if (t<=0 or t>=l): sd=math.copysign(dd, sd if sd!=0 else 1)
                    best=(dd,(off+t)/tot,sd)
            _,u,sd=best
            if abs(sd)>half: continue
            sp=S+ax*u+nx*sd
            sx,sy=int(round(sp[0])),int(round(sp[1]))
            if 0<=sx<SW and 0<=sy<SH and mask[sy,sx]: out[y,x]=sa[sy,sx]
    return out
canvas=np.zeros((H,W,4),np.uint8); canvas[PT:PT+SH,PL:PL+SW]=body
img=Image.fromarray(canvas)
if mode=='bless':
    # her left (on the right): out at the shoulder, forearm straight up, the palm raised in blessing
    Rarm=grow(Rm,(418,318),(455,765),[P(372,348),P(482,240),P(464,52),P(460,-52)],40)
    # her right (on the left): elbow out, forearm back across, the hand on her belly with the dagger
    Larm=grow(Lm,(66,318),(36,765),[P(112,348),P(30,522),P(150,566),P(206,590)],40)
    hand_grip=P(174,578)
    img=Image.alpha_composite(img,Image.fromarray(Rarm))
else:
    # last stand: the arms hang ready, the dagger held out low
    Larm=np.zeros((H,W,4),np.uint8); Larm[PT:PT+SH,PL:PL+SW][Lm]=sa[Lm]
    Rarm=np.zeros((H,W,4),np.uint8); Rarm[PT:PT+SH,PL:PL+SW][Rm]=sa[Rm]
    img=Image.alpha_composite(img,Image.fromarray(Rarm))
    hand_grip=P(42,712)
# ---- the dagger, then the hand over its grip
dag=Image.new('RGBA',(W,H),(0,0,0,0)); dd=dag.load()
def put(x,y,c):
    x=int(round(x)); y=int(round(y))
    if 0<=x<W and 0<=y<H: dd[x,y]=c
INK=(36,28,44,255)
gx,gy=hand_grip
if mode=='bless': top=(gx+4,gy-46); tip=(gx-6,gy+150)
else: top=(gx+10,gy-40); tip=(gx-16,gy+150)
ang=math.atan2(tip[1]-top[1],tip[0]-top[0]); ux,uy=math.cos(ang),math.sin(ang); nx,ny=-uy,ux; L=math.hypot(tip[0]-top[0],tip[1]-top[1])
for i in range(int(L*2)+1):
    t=i/2; px=top[0]+ux*t; py=top[1]+uy*t
    if t<12:
        rr=7.5-abs(t-6)*0.6
        for k in range(-9,10):
            if abs(k)<=rr: put(px+nx*k,py+ny*k,(228,150,178,255) if k<0 else (190,104,140,255))
            elif abs(k)<=rr+1.2: put(px+nx*k,py+ny*k,INK)
    elif t<56:
        for k in range(-6,7): put(px+nx*k,py+ny*k,INK if abs(k)==6 else ((80,128,62,255) if (int(t)+k)%7<3 else (112,72,42,255)))
    elif t<65:
        for k in range(-20,21): put(px+nx*k,py+ny*k,INK if abs(k)==20 else ((92,172,76,255) if abs(k)>=16 else ((244,208,84,255) if t<61 else (170,134,34,255))))
    else:
        f=(t-65)/(L-65); half=8.5*(1-f**1.5)+0.7
        for k in range(-10,11):
            if abs(k)>half+1.2: continue
            if abs(k)>half: put(px+nx*k,py+ny*k,INK); continue
            c=(226,232,242,255) if k<0 else (148,158,178,255)
            if abs(k)<1 and f<0.75: c=(118,126,146,255)
            if k<=-half+1.2: c=(252,253,255,255)
            put(px+nx*k,py+ny*k,c)
img=Image.alpha_composite(img,dag)
img=Image.alpha_composite(img,Image.fromarray(Larm))
img=img.crop(img.getbbox())
img.save('model_%s.png'%mode); print(mode,img.size)

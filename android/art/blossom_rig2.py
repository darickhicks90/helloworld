import math, cv2, numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageEnhance
base=Image.open('cut.png').convert('RGBA'); W,H=base.size
arr=np.array(base)
# ---- the flower off her forehead (it moves above her head), the lips toned down, the eyes bluer
rgb=arr[:,:,:3].astype(int); r,g,b=rgb[:,:,0],rgb[:,:,1],rgb[:,:,2]
pink=(r>g+28)&(r>b+5)&(arr[:,:,3]>0)
ys,xs=np.where(pink)
flower=np.zeros(pink.shape,np.uint8); lips=np.zeros(pink.shape,np.uint8)
for y,x in zip(ys,xs):
    (flower if y<120 else lips)[y,x]=1
flower=cv2.dilate(flower,np.ones((7,7),np.uint8))
bgr=cv2.cvtColor(arr[:,:,:3],cv2.COLOR_RGB2BGR)
bgr=cv2.inpaint(bgr,flower*255,6,cv2.INPAINT_TELEA)
out=cv2.cvtColor(bgr,cv2.COLOR_BGR2RGB).astype(int)
# lips: most of the way back toward her fur, a soft rose remaining
lm=lips>0
for c,t in ((0,214),(1,190),(2,186)): out[:,:,c]=np.where(lm,(out[:,:,c]*0.35+t*0.65).astype(int),out[:,:,c])
# the five eyes: bluer and deeper
blue=(out[:,:,2]>out[:,:,0]+18)&(arr[:,:,3]>0)
out[:,:,0]=np.where(blue,(out[:,:,0]*0.55).astype(int),out[:,:,0]); out[:,:,1]=np.where(blue,(out[:,:,1]*0.82).astype(int),out[:,:,1]); out[:,:,2]=np.where(blue,np.minimum(255,out[:,:,2]*1.12).astype(int),out[:,:,2])
arr[:,:,:3]=np.clip(out,0,255)
up=Image.fromarray(arr)
# ---- her legs from the 3D model: sturdy, straight, feet planted
mod=Image.open('model_front.png').convert('RGBA'); ma=np.array(mod)
ma[:760,:80,3]=0; ma[:760,420:,3]=0          # not the hands hanging at her sides
legs=Image.fromarray(ma).crop((75,640,425,mod.height))
k=210/332.0
legs=legs.resize((round(legs.width*k),round(legs.height*k)),Image.LANCZOS)
la=np.array(legs).astype(float)
# tinted from the model's warm cream to the drawing's paper white, a little sketchier (more contrast in the fur)
la[:,:,0]=la[:,:,0]*0.98; la[:,:,1]=la[:,:,1]*1.02; la[:,:,2]=la[:,:,2]*1.05
m=la[:,:,:3].mean(axis=2,keepdims=True); la[:,:,:3]=np.clip((la[:,:,:3]-m)*0.6+m+(la[:,:,:3].mean()*0+8),0,255)
legs=Image.fromarray(la.astype(np.uint8))
CUT=482; cx=176
newH=CUT+legs.height-6
comp=Image.new('RGBA',(W,newH),(0,0,0,0))
upper=up.crop((0,0,W,CUT+12)); ua=np.array(upper)
# fade the drawing's hips into the model's over the last 12 rows
for i in range(12): ua[CUT+i,:,3]=(ua[CUT+i,:,3]*(1-i/12)).astype(np.uint8)
comp.alpha_composite(Image.fromarray(ua),(0,0))
lx=cx-legs.width//2
legsl=Image.new('RGBA',comp.size,(0,0,0,0)); legsl.alpha_composite(legs,(lx,CUT-6))
lla=np.array(legsl); 
for i in range(10): lla[CUT-6+i,:,3]=(lla[CUT-6+i,:,3]*(i/10)).astype(np.uint8)
comp=Image.alpha_composite(Image.fromarray(lla),comp)
comp.alpha_composite(Image.fromarray(lla[:, :, :]*0+0) if False else Image.new('RGBA',comp.size,(0,0,0,0)))
# the dagger again (rose pommel, vine grip, gold guard, steel blade) and her fingers over its grip
dag=Image.new('RGBA',comp.size,(0,0,0,0)); d=dag.load(); CW,CH=comp.size
def put(x,y,c):
    x=int(round(x)); y=int(round(y))
    if 0<=x<CW and 0<=y<CH: d[x,y]=c
INK=(32,24,40,255)
gx0,gy0=158,312; gx1,gy1=150,488
ang=math.atan2(gy1-gy0,gx1-gx0); ux,uy=math.cos(ang),math.sin(ang); nx,ny=-uy,ux; L=math.hypot(gx1-gx0,gy1-gy0)
for i in range(int(L*2)+1):
    t=i/2; px_=gx0+ux*t; py_=gy0+uy*t
    if t<10:
        rr=6.5-abs(t-5)*0.6
        for kk in range(-7,8):
            if abs(kk)<=rr: put(px_+nx*kk,py_+ny*kk,(222,140,170,255) if kk<0 else (186,100,136,255))
            elif abs(kk)<=rr+1: put(px_+nx*kk,py_+ny*kk,INK)
    elif t<50:
        for kk in range(-5,6): put(px_+nx*kk,py_+ny*kk,INK if abs(kk)==5 else ((74,122,58,255) if (int(t)+kk)%7<3 else (110,70,40,255)))
    elif t<58:
        for kk in range(-17,18): put(px_+nx*kk,py_+ny*kk,(88,168,72,255) if abs(kk)>=15 else ((240,204,80,255) if t<54 else (168,132,32,255)))
    else:
        f=(t-58)/(L-58); half=7*(1-f**1.6)+0.6
        for kk in range(-8,9):
            if abs(kk)>half+1: continue
            if abs(kk)>half: put(px_+nx*kk,py_+ny*kk,INK); continue
            c=(224,230,240,255) if kk<0 else (150,160,178,255)
            if abs(kk)<0.8 and f<0.75: c=(120,128,146,255)
            if kk<=-half+1: c=(250,252,255,255)
            put(px_+nx*kk,py_+ny*kk,c)
bp=up.load(); comp.alpha_composite(dag); cp=comp.load()
def lum(c): return 0.3*c[0]+0.59*c[1]+0.11*c[2]
for y in range(318,366):
    for x in range(136,172):
        c=bp[x,y]
        if c[3] and lum(c)<175 and abs(x-154)<14: cp[x,y]=c
comp.save('her_rig2.png'); print('rig',comp.size)

import numpy as np, cv2
from PIL import Image, ImageEnhance, ImageFilter
OUT=(46,34,54)
def outline(a, col=OUT):
    al=a[:,:,3]>0; e=np.zeros_like(al)
    e[1:,:]|=al[:-1,:]; e[:-1,:]|=al[1:,:]; e[:,1:]|=al[:,:-1]; e[:,:-1]|=al[:,1:]
    e&=~al; a[e,:3]=col; a[e,3]=255; return a
def pad(a,n=2):
    h,w=a.shape[:2]; o=np.zeros((h+2*n,w+2*n,4),np.uint8); o[n:n+h,n:n+w]=a; return o
def masks(a):
    r,g,b=[a[:,:,i].astype(int) for i in range(3)]; op=a[:,:,3]>0
    h,w=op.shape; ys=np.where(op.any(1))[0]; top=ys.min()
    head=np.zeros_like(op); head[top:top+int((ys.max()-top)*0.34), int(w*0.12):int(w*0.82)]=True
    blue=op&head&(b>r+6)&(b>=g-2)&(r<215)
    pink=op&head&(r>g+30)&(r>b+12)
    gold=op&(r>170)&(g>140)&(b<110)
    green=op&(g>r+10)&(g>b+5)
    steel=op&(b>=r)&(abs(r-g)<16)&(r>130)&(r<235)&~blue
    return blue,pink,gold,green
def accents(src, k):
    """Where her eyes, flowers and dagger are, scaled into the small picture."""
    a=np.array(Image.open(src).convert('RGBA')); blue,pink,gold,green=masks(a)
    def blobs(m, n, minarea):
        num,lab,st,cen=cv2.connectedComponentsWithStats(cv2.dilate(m.astype(np.uint8),np.ones((5,5),np.uint8)),8)
        order=sorted(range(1,num),key=lambda i:-st[i,cv2.CC_STAT_AREA])
        return [(cen[i][0]*k,cen[i][1]*k,st[i,cv2.CC_STAT_AREA]) for i in order[:n] if st[i,cv2.CC_STAT_AREA]>=minarea]
    return blobs(blue,5,20), blobs(pink,3,20), blobs(gold,2,10)

EYE_OFFS=[(-40,82),(58,82),(-39,114),(54,114)]
def great_eye(src):
    a=np.array(Image.open(src).convert('RGBA')); blue,_,_,_=masks(a)
    num,lab,st,cen=cv2.connectedComponentsWithStats(cv2.dilate(blue.astype(np.uint8),np.ones((5,5),np.uint8)),8)
    i=1+np.argmax(st[1:,cv2.CC_STAT_AREA]); return cen[i]
def paint_irises(src, H, img):
    # a blue iris, a dark pupil and a glint in each of the four lower eyes (they're pale on the model)
    from PIL import ImageDraw
    im=Image.open(src); k=H/im.height; gx,gy=great_eye(src); d=ImageDraw.Draw(img)
    for (ox,oy) in EYE_OFFS:
        x=(gx+ox)*k+2; y=(gy+oy)*k+2; r=7.5*k*1.25
        d.ellipse((x-r,y-r,x+r,y+r),fill=(70,128,214,255)); d.ellipse((x-r*0.45,y-r*0.45,x+r*0.45,y+r*0.45),fill=(26,30,60,255))
        d.rectangle((x-r*0.7,y-r*0.7,x-r*0.7+max(1,r*0.35),y-r*0.7+max(1,r*0.35)),fill=(255,255,255,255))
    return img

# ============== SNES-quality duel picture
def snes(src, H=260, colors=48):
    im=Image.open(src).convert('RGBA'); w=round(im.width*H/im.height); im=im.resize((w,H),Image.LANCZOS)
    a=np.array(im); a[:,:,3]=np.where(a[:,:,3]<100,0,255)
    blue,pink,gold,green=masks(a); acc=blue|pink|gold|green
    f=a[:,:,:3].astype(float)
    hh,ww=f.shape[:2]; yy,xx=np.mgrid[0:hh,0:ww]; k=(1.05-0.11*(xx/ww)-0.05*(yy/hh))[:,:,None]
    fur=np.clip((f-128)*1.18+128,0,255)*k
    rgb=Image.fromarray(np.clip(fur,0,255).astype(np.uint8))
    q=np.array(rgb.quantize(colors=colors,method=Image.Quantize.MEDIANCUT).convert('RGB')).astype(float)
    # the colour accents keep their own colours, a little richer
    rich=f.copy(); r,g,b=rich[:,:,0],rich[:,:,1],rich[:,:,2]
    rich[blue]=np.stack([r*0.4,g*0.75,np.minimum(255,b*1.2)],-1)[blue]
    rich[pink]=np.stack([np.minimum(255,r*1.05),g*0.8,b*0.95],-1)[pink]
    q[acc]=rich[acc]
    out=np.zeros((hh,ww,4),np.uint8); out[:,:,:3]=np.clip(q,0,255); out[:,:,3]=a[:,:,3]
    return outline(pad(out,2))
d=snes('model_bless.png'); Image.fromarray(d).save('duel_snes.png')
img=paint_irises('model_bless.png', 260, Image.open('duel_snes.png').convert('RGBA')); img.save('duel_snes.png'); d=np.array(img)
al=d[:,:,3]>0; half=d.shape[1]//2; ys,xs=np.where(al[:,half:]); top=ys.min(); hx=xs[ys==top].mean()+half
print('duel',d.shape[1],d.shape[0],'hand',round(hx/d.shape[1],3),round((top+8)/d.shape[0],3))

# ============== Game Boy Color-style hall sprite
FUR=[(255,252,246),(242,230,220),(216,198,188),(172,150,146)]
WHITE=(250,250,255); BLUE=(64,128,224); BLUEL=(150,196,246); PUP=(30,34,70); LID=(120,100,108)
PINK=(240,146,176); PINKD=(198,100,136); GOLD=(242,204,84); STEEL=(222,230,242); STEELD=(140,150,170); GREEN=(108,140,88)
def gbc(src, H=62):
    im=Image.open(src).convert('RGBA'); k=H/im.height; w=round(im.width*k)
    # smooth the fur strokes away first: Game Boy Color shading comes in clean bands
    sm=im.filter(ImageFilter.GaussianBlur(radius=1/k*0.9))
    sm=sm.resize((w,H),Image.LANCZOS); a=np.array(sm).astype(float)
    alpha=np.array(im.resize((w,H),Image.LANCZOS))[:,:,3]>110
    out=np.zeros((H,w,4),np.uint8)
    for y in range(H):
        row=np.where(alpha[y])[0]
        if not len(row): continue
        for x in row:
            l=a[y,x,:3].mean(); t=(x-row.min())/max(1,row.max()-row.min())
            l=l*(1.03-0.09*t)
            c=FUR[0] if l>218 else FUR[1] if l>196 else FUR[2] if l>164 else FUR[3]
            out[y,x,:3]=c; out[y,x,3]=255
    out=pad(out,2)
    eyesL,pinks,golds=accents(src,k)
    gx,gy=great_eye(src); eyesL=[(gx*k,gy*k,0)]+[((gx+ox)*k,(gy+oy)*k,0) for (ox,oy) in EYE_OFFS]
    def put(x,y,c):
        x=int(round(x))+2; y=int(round(y))+2
        if 0<=y<out.shape[0] and 0<=x<out.shape[1] and out[y,x,3]: out[y,x,:3]=c
    if eyesL:
        eyesL=sorted(eyesL,key=lambda e:e[1])
        ex,ey,_=eyesL[0]                       # the great eye, highest on her face
        for d in (-2,-1,0,1,2): put(ex+d,ey,WHITE)
        for d in (-1,0,1): put(ex+d,ey+1,WHITE); put(ex+d,ey-1,LID)
        put(ex-1,ey,BLUE); put(ex,ey,PUP); put(ex+1,ey,BLUE); put(ex,ey+1,BLUE)
        for (x,y,_) in eyesL[1:]:
            put(x-0.5,y,BLUE); put(x+0.5,y,BLUEL); put(x-0.5,y-1,LID); put(x+0.5,y-1,LID)
    for (x,y,_) in pinks:                      # her flowers
        for (dx,dy) in ((0,-1),(-1,0),(1,0),(0,1)): put(x+dx,y+dy,PINK)
        put(x,y,GOLD); put(x+1,y+1,PINKD); put(x,y+2,GREEN)
    for (x,y,_) in golds[:1]:                  # the dagger's guard, and its blade below
        for dx in (-1,0,1): put(x+dx,y,GOLD)
        for i in range(1,6): put(x-(0.3*i if 'ready' in src else 0.1*i),y+i,STEEL if i<5 else STEELD)
    return outline(out)
for pose in ('bless','ready'):
    s=gbc('model_%s.png'%pose); Image.fromarray(s).save('gbc_%s.png'%pose); print(pose,s.shape[1],s.shape[0])

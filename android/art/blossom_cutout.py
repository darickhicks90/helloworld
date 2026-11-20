import sys, cv2, numpy as np
src=cv2.imread(sys.argv[1]); H,W=src.shape[:2]
im=cv2.resize(src,(W//3,H//3),interpolation=cv2.INTER_AREA); h,w=im.shape[:2]
b,g,r=[im[:,:,i].astype(int) for i in range(3)]
lum=0.3*r+0.59*g+0.11*b
blur=cv2.blur(im,(5,5)); bb,gg,rr=[blur[:,:,i].astype(int) for i in range(3)]
warm=(rr-gg>=2)&(lum>228)
cool=(gg-rr>=0)&(lum>190)&(lum<222)
ink=(lum<140)
colour=(np.max(im,2).astype(int)-np.min(im,2).astype(int)>40)
mask=np.full((h,w),cv2.GC_PR_BGD,np.uint8)
# a probable figure: the central column where she stands
mask[:, int(w*0.2):int(w*0.85)]=cv2.GC_PR_FGD
mask[cool]=cv2.GC_PR_BGD
# sure figure: warm paper-white well inside, the eyes, flower, lips
k=cv2.erode((warm).astype(np.uint8),np.ones((5,5),np.uint8))
mask[k>0]=cv2.GC_FGD; mask[colour]=cv2.GC_FGD
# sure paper: the margins
mask[:, :int(w*0.08)]=cv2.GC_BGD; mask[:, int(w*0.95):]=cv2.GC_BGD; mask[:4,:]=cv2.GC_BGD; mask[-3:,:]=cv2.GC_BGD
bgm=np.zeros((1,65),np.float64); fgm=np.zeros((1,65),np.float64)
cv2.grabCut(im,mask,None,bgm,fgm,8,cv2.GC_INIT_WITH_MASK)
fg=((mask==cv2.GC_FGD)|(mask==cv2.GC_PR_FGD)).astype(np.uint8)
# keep the biggest piece; fill holes
n,lab,stats,_=cv2.connectedComponentsWithStats(fg,8)
big=1+np.argmax(stats[1:,cv2.CC_STAT_AREA]); fg=(lab==big).astype(np.uint8)
inv=1-fg; n2,lab2,st2,_=cv2.connectedComponentsWithStats(inv,4)
for i in range(1,n2):
    x,y,ww,hh,a=st2[i]
    if not (x==0 or y==0 or x+ww==w or y+hh==h) and a<1500 and np.mean(lum[lab2==i])>200: fg[lab2==i]=1
rgba=cv2.cvtColor(im,cv2.COLOR_BGR2BGRA); rgba[:,:,3]=fg*255
ys,xs=np.where(fg>0); rgba=rgba[ys.min():ys.max()+1, xs.min():xs.max()+1]
cv2.imwrite(sys.argv[2],rgba); print(rgba.shape)

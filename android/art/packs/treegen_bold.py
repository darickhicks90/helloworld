"""Trees in a bold, saturated pixel style: leaf clumps with dark outlines, yellow-green lights with
speckle, deep teal shadows; thick, twisting red-brown trunks with purple shade, forking into the
crown; flared roots in tufts of grass."""
import math, random
from PIL import Image
CW,CH=64,64; G=61
INK=(18,22,26)
def R(*h): return [tuple(int(x[i:i+2],16) for i in (0,2,4)) for x in h]
LEAF=R('16343A','1E4E46','2E6E3E','4C8E34','7AB02E','B6D446')        # deep teal .. yellow-green
LEAF2=R('142C3A','1C4250','2A5E48','3E7E3C','64A038','9CC64C')
OLIVE=R('283420','3E4C24','5A6A26','7E8C2A','A6AE36','D2D466')
AUT=R('3A1E24','5E2A22','8E4222','C26A26','E89A34','F8CC5C')
BARK=R('2A1418','4A2228','6E3A2E','9A5432','C47A44','E0A062')
BARKP=(88,48,78)   # the purple of shade on bark
GRASS=R('1E3E2A','2E6232','4C8E34','7AB02E','B6D446')
class T:
    def __init__(s,seed): s.r=random.Random(seed); s.g=[[None]*CW for _ in range(CH)]; s.part=[[-1]*CW for _ in range(CH)]
    def px(s,x,y,c,part=-2):
        x=int(x); y=int(y)
        if 0<=x<CW and 0<=y<CH: s.g[y][x]=c; s.part[y][x]=part
    def get(s,x,y): return s.g[y][x] if 0<=x<CW and 0<=y<CH else None
    # ---- the trunk: a sinuous centre line, thick, flaring to roots
    def trunk(s,cx,top,w,lean,twist):
        pts=[]
        for y in range(top,G+1):
            t=(y-top)/(G-top)
            x=cx+lean*(1-t)*8+math.sin(t*math.pi*1.6+twist)*2.2
            hw=w/2*(1+max(0,(t-0.7)/0.3)**2*1.8)
            for xx in range(int(x-hw),int(x+hw)+1):
                u=(xx-(x-hw))/max(1,2*hw)
                c=BARK[4] if u<0.22 else BARK[3] if u<0.45 else BARK[2] if u<0.7 else BARKP if u<0.88 else BARK[1]
                if (xx*5+y*2)%9==0 and 0.25<u<0.8: c=BARK[1]
                if (y*3+xx)%13==0 and u<0.4: c=BARK[5]
                s.px(xx,y,c,-3)
            pts.append((x,y,hw))
        # roots
        for k,side in enumerate((-1,1,-1,1)):
            ln=4+s.r.randint(2,6); y0=G-1-k%2
            for j in range(ln):
                s.px(cx+side*(w*0.9+j),y0+(1 if j>ln*0.6 else 0),BARK[2 if j%3 else 1],-3)
                s.px(cx+side*(w*0.9+j),y0-1,BARK[3] if j<ln-2 else None,-3) if j<ln-2 else None
        return pts
    def limb(s,x0,y0,x1,y1,w):
        n=int(max(abs(x1-x0),abs(y1-y0)))+1
        for i in range(n+1):
            t=i/n; x=x0+(x1-x0)*t+math.sin(t*3)*1.2; y=y0+(y1-y0)*t; ww=w*(1-t*0.6)
            for d in range(-int(ww),int(ww)+1):
                s.px(x+d,y,BARK[4] if d<0 else BARK[2] if d==0 else BARKP,-3)
    # ---- a clump: a lumpy blob, lit as a whole from the upper left, speckled, dark at its foot
    def clump(s,cx,cy,rx,ry,pal,pid,shade=0.0):
        lumps=[(cx,cy,rx,ry)]+[(cx+s.r.uniform(-rx*0.6,rx*0.6),cy+s.r.uniform(-ry*0.5,ry*0.3),rx*s.r.uniform(0.45,0.7),ry*s.r.uniform(0.45,0.7)) for _ in range(4)]
        def inside(x,y):
            for (a,b,c,d) in lumps:
                # ragged edges: a little noise on the radius
                k=1+0.12*math.sin(x*1.9+y*1.3+pid)
                if ((x+0.5-a)/(c*k))**2+((y+0.5-b)/(d*k))**2<=1: return True
            return False
        for y in range(int(cy-ry-3),int(cy+ry+3)):
            for x in range(int(cx-rx-3),int(cx+rx+3)):
                if not inside(x,y): continue
                nx=(x-cx)/rx; ny=(y-cy)/ry
                l=0.55-(nx*0.45+ny*0.85)*0.5-shade
                n=s.r.random()
                if n<0.12: l-=0.18
                elif n>0.9: l+=0.16
                i=0 if l<0.05 else 1 if l<0.2 else 2 if l<0.38 else 3 if l<0.56 else 4 if l<0.74 else 5
                s.px(x,y,pal[i],pid)
    def outline(s):
        q=[r[:] for r in s.g]; qp=[r[:] for r in s.part]
        for y in range(CH):
            for x in range(CW):
                if q[y][x] is None:
                    if any(0<=x+dx<CW and 0<=y+dy<CH and q[y+dy][x+dx] is not None for dx,dy in((1,0),(-1,0),(0,1),(0,-1))): s.g[y][x]=INK
                    continue
                # inner outlines: under each clump's edge where it overlaps one behind it
                p=qp[y][x]
                if p>=0 and 0<=y+1<CH and qp[y+1][x]>=0 and qp[y+1][x]<p and q[y+1][x] is not None:
                    s.g[y+1][x]=INK if s.r.random()<0.9 else q[y+1][x]
                    if 0<=y+2<CH and qp[y+2][x]>=0 and s.r.random()<0.5: s.g[y+2][x]=q[y+2][x] and (max(0,q[y+2][x][0]-30),max(0,q[y+2][x][1]-30),max(0,q[y+2][x][2]-20))
    def grass(s,cx,w):
        for k in range(int(w*1.4)):
            x=cx-w*0.7+k+s.r.uniform(-0.5,0.5); h=s.r.randint(1,4)
            for j in range(h): s.px(x,G+1-j,GRASS[min(4,1+j+(k%2))],-3)
        for x in range(int(cx-w*0.8),int(cx+w*0.8)): s.px(x,G+1,GRASS[0],-3)
    def img(s):
        im=Image.new('RGBA',(CW,CH),(0,0,0,0))
        for y in range(CH):
            for x in range(CW):
                if s.g[y][x] is not None: im.putpixel((x,y),s.g[y][x]+(255,))
        return im

def broad(seed,pal=LEAF,wide=1.0,lean=0.0,height=0,twist=0.0):
    t=T(seed); r=t.r
    top=32+height
    t.trunk(32,top,9,lean,twist)
    # limbs forking up into the crown
    t.limb(32+lean*4,top+6,16,top-8,3); t.limb(32+lean*4,top+4,48,top-10,3); t.limb(32,top+2,31,top-16,2.5)
    cy=21+height*0.5; rx=29*wide; ry=19
    clumps=[]
    for i in range(14):
        a=-math.pi/2+(i-6.5)*0.27+r.uniform(-0.15,0.15); d=r.uniform(0.35,0.85)
        clumps.append((32+math.cos(a)*rx*d*1.0, cy+math.sin(a)*ry*0.75*d+ (7 if i%3==0 else 0) + r.uniform(-2,2), r.uniform(7,10.5), r.uniform(5.5,7.5)))
    clumps.sort(key=lambda c:-c[1])   # lower ones first (they're behind)... then the crown in front
    clumps.sort(key=lambda c:c[1])
    for pid,(x,y,a,b) in enumerate(sorted(clumps,key=lambda c:c[1]*-1)):
        t.clump(x,y,a,b,pal,pid,shade=0.28 if y>cy+5 else 0.1 if y>cy else 0.0)
    # bits of trunk showing between the lower clumps
    for (x,y) in [(26,top-3),(38,top-4)]:
        for k in range(3):
            if t.get(int(x),int(y+k)) in pal[:2]: t.px(x,y+k,BARK[2],-3)
    t.grass(32,9); t.outline()
    return t.img()
def sheet():
    trees=[broad(1),broad(2,wide=1.05,lean=0.6,twist=1.0),broad(3,height=2,twist=2.0),broad(4,pal=LEAF2,lean=-0.5),broad(5,pal=OLIVE,twist=0.5),
           broad(6,wide=0.9,height=-2,lean=0.8,twist=3.0),broad(7,pal=LEAF2,wide=1.1),broad(8,lean=-0.8,twist=1.5),broad(9,pal=OLIVE,wide=0.95,lean=0.4),
           broad(10,pal=AUT),broad(11,height=3,wide=0.85),broad(12,pal=LEAF2,lean=0.5,twist=2.5)]
    sh=Image.new('RGBA',(CW*len(trees),CH),(0,0,0,0))
    for i,im in enumerate(trees): sh.alpha_composite(im,(i*CW,0))
    return sh,trees
if __name__=='__main__':
    sh,ts=sheet(); sh.save('wild_trees.png')
    bg=Image.new('RGBA',sh.size,(90,80,82,255)); bg.alpha_composite(sh)
    w=sh.width//2
    o=Image.new('RGBA',(w,CH*2+4),(90,80,82,255)); o.paste(bg.crop((0,0,w,CH)),(0,0)); o.paste(bg.crop((w,0,sh.width,CH)),(0,CH+4))
    o.resize((o.width*3,o.height*3),Image.NEAREST).save('preview.png'); print('ok')

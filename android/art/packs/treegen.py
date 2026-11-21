"""Painterly pixel trees: foliage built from many small leaf clusters (each lit on its own upper
left, the whole crown lit as a ball), no ink outline, ragged edges, flared roots; broadleaves,
spruces, firs, pines, a gnarled olive, birch, autumn and blossom. Every tree from a seed."""
import math, random
from PIL import Image
CW,CH=40,56; GROUND=54
def rgb(h): return tuple(int(h[i:i+2],16) for i in (0,2,4))
def R(*hs): return [rgb(h) for h in hs]
OAK=R('163A24','24562E','357238','4E9040','76B252','A8D070')
LIME=R('2E4418','46631E','628426','84A42E','A8C048','D0DE7C')
OLIVE=R('3A4818','566420','768426','98A232','BCC048','E0DE80')
SPRUCE=R('0E2E32','164444','205C58','2E786C','4C9880','80BCA0')
FIR=R('0E2A1A','163E24','22562E','30703A','4C904A','7CB066')
PINE=R('14301E','1E4628','2C6034','3E7A3E','5E9A4E','8EBA6C')
AUTUMN=R('5A2410','8A3A14','B85A1C','DC8226','F0AC40','F8D47C')
BLOSSOM=R('0C2418','143620','1E4C2A','2C6434','467E44','6A9C5E')   # a dark yew (no longer pink)
BIRCHL=R('3E5018','5A7020','7A9228','A0B238','C4CE5C','E4E896')
BARK=R('2A1810','46281A','623A24','7E5032','9C6A44')
GREYBARK=R('3A3630','5E5850','86806E','AAA48E','CEC8B0')
BIRCH=R('3A3634','8A8680','C8C4B8','E8E6DC','FAF8F2')
REDBARK=R('2A1410','4A2018','6C3022','8C4430','A85E40')

class Tree:
    def __init__(s,seed):
        s.r=random.Random(seed); s.g=[[None]*CW for _ in range(CH)]
    def px(s,x,y,c):
        x=int(x); y=int(y)
        if 0<=x<CW and 0<=y<CH: s.g[y][x]=c
    def get(s,x,y): return s.g[y][x] if 0<=x<CW and 0<=y<CH else None
    # ---- trunks
    def trunk(s,cx,top,w,rp,roots=3,lean=0.0,flare=1.0):
        for y in range(int(top),GROUND+1):
            t=(y-top)/max(1,GROUND-top)
            fl=1+flare*max(0,(t-0.6)/0.4)**2*2.0
            hw=w/2*fl; x0=cx+lean*(1-t)*6
            for x in range(int(x0-hw),int(x0+hw)+1):
                u=(x-(x0-hw))/max(1,2*hw)
                i=4 if u<0.18 else 3 if u<0.4 else 2 if u<0.7 else 1
                if (x*7+y*3)%11==0 and 0.2<u<0.8: i=max(0,i-1)   # bark grooves
                s.px(x,y,rp[i])
        # roots creeping out over the ground
        for k in range(roots):
            side=-1 if k%2==0 else 1; ln=4+s.r.randint(1,4)
            for j in range(ln):
                s.px(cx+side*(w/2*2.2+j),GROUND-1+(j>ln//2),rp[1 if j%2 else 2])
        for x in range(int(cx-w*1.4),int(cx+w*1.4)+1): s.px(x,GROUND+1,rp[0])
    def branch(s,x0,y0,x1,y1,rp,wd=1):
        n=int(max(abs(x1-x0),abs(y1-y0)))+1
        for i in range(n+1):
            t=i/n; x=x0+(x1-x0)*t; y=y0+(y1-y0)*t
            for d in range(wd): s.px(x+d,y,rp[2 if d==0 else 1])
    # ---- foliage
    def crown(s,mask,cx,cy,rx,ry,rp,cl=2.3,dens=1.0,needle=False):
        """mask(x,y)->bool: the crown's shape. Fill dark, then lay clusters back to front."""
        pts=[(x,y) for y in range(CH) for x in range(CW) if mask(x,y)]
        for (x,y) in pts:
            ny=(y-cy)/ry; s.px(x,y,rp[0] if ny>0.2 else rp[1])
        step=cl*1.15/dens; cs=[]
        yy=min(p[1] for p in pts)-1
        while yy<max(p[1] for p in pts)+2:
            xx=min(p[0] for p in pts)-1+(s.r.random()*step)
            while xx<max(p[0] for p in pts)+2:
                jx=xx+s.r.uniform(-0.6,0.6); jy=yy+s.r.uniform(-0.6,0.6)
                if mask(int(jx),int(jy)): cs.append((jx,jy))
                xx+=step
            yy+=step*0.85
        cs.sort(key=lambda c:c[1])
        for (x0,y0) in cs:
            nx=(x0-cx)/rx; ny=(y0-cy)/ry
            glob=0.55-(nx*0.45+ny*0.75)*0.55 - max(0,nx*nx+ny*ny-0.7)*0.25
            glob+=s.r.uniform(-0.12,0.12)
            if s.r.random()<0.12: glob-=0.4           # a pocket of shade
            r=cl*s.r.uniform(0.8,1.15)
            for y in range(int(y0-r-1),int(y0+r+2)):
                for x in range(int(x0-r-1),int(x0+r+2)):
                    dx=x+0.5-x0; dy=y+0.5-y0
                    if needle: inside=(dx/(r*1.5))**2+(dy/(r*0.7))**2<=1
                    else: inside=dx*dx+dy*dy<=r*r
                    if not inside or not mask(x,y): continue
                    loc=-(dx+dy)/(2*r)*0.55
                    v=glob+loc
                    i=0 if v<0.12 else 1 if v<0.3 else 2 if v<0.5 else 3 if v<0.68 else 4 if v<0.86 else 5
                    s.px(x,y,rp[i])
        # ragged edge: leaf tips poking out; the shadow side's rim one tone darker
        for (x,y) in pts:
            if not mask(x,y+1) and s.r.random()<0.35: s.px(x,y+1,rp[0])
            if not mask(x,y-1) and s.r.random()<0.25: s.px(x,y-1,rp[2] if x<cx else rp[1])
            if not mask(x-1,y) and s.r.random()<0.2: s.px(x-1,y,rp[1])
            if not mask(x+1,y) and s.r.random()<0.2: s.px(x+1,y,rp[0])
    def img(s):
        im=Image.new('RGBA',(CW,CH),(0,0,0,0))
        for y in range(CH):
            for x in range(CW):
                if s.g[y][x]: im.putpixel((x,y),s.g[y][x]+(255,))
        return im

def lumps(cx,cy,rx,ry,r,n,spread=1.0):
    blobs=[(cx,cy,rx,ry)]
    for k in range(n):
        a=-math.pi/2+(k-(n-1)/2)*(2.6/max(1,n-1))+r.uniform(-0.25,0.25)
        d=r.uniform(0.55,0.8)
        blobs.append((cx+math.cos(a)*rx*d*spread,cy+math.sin(a)*ry*d,rx*r.uniform(0.42,0.6),ry*r.uniform(0.42,0.6)))
    def m(x,y):
        return any(((x+0.5-bx)/bw)**2+((y+0.5-by)/bh)**2<=1 for bx,by,bw,bh in blobs)
    return m

def broadleaf(seed,rp,bark=BARK,tall=False,wide=1.0,roots=3,trunkw=5):
    t=Tree(seed); r=t.r
    cy=27 if not tall else 24; rx=15*wide; ry=15 if not tall else 19
    t.trunk(20,cy+4,trunkw,bark,roots=roots,flare=1.0)
    t.branch(20,cy+8,13,cy+2,bark,2); t.branch(20,cy+8,27,cy+1,bark,2)
    m=lumps(20,cy,rx,ry,r,r.randint(4,6))
    t.crown(m,20,cy,rx,ry,rp,cl=2.9)
    # the trunk in shade right under the crown
    for y in range(cy+int(ry*0.6),cy+int(ry*0.6)+3):
        for x in range(16,25):
            c=t.get(x,y)
            if c in bark: t.px(x,y,bark[0])
    return t.img()

def twisted(seed,rp=OLIVE,bark=GREYBARK):
    t=Tree(seed); r=t.r
    # a gnarled, leaning trunk that splits in two
    pts=[(20,GROUND),(21,48),(19,43),(22,38),(20,33)]
    for (a,b) in zip(pts,pts[1:]): t.branch(a[0]-1,a[1],b[0]-1,b[1],bark,3)
    t.branch(20,33,12,24,bark,2); t.branch(21,33,28,25,bark,2); t.branch(14,27,9,22,bark,1)
    for x in range(15,26): t.px(x,GROUND,bark[1])
    t.px(14,GROUND-1,bark[2]); t.px(26,GROUND-1,bark[1])
    blobs=[(12,20,9,7),(27,19,9,7),(20,14,8,7),(6,23,5,4),(33,23,5,4)]
    m=lambda x,y: any(((x+0.5-bx)/bw)**2+((y+0.5-by)/bh)**2<=1 for bx,by,bw,bh in blobs)
    t.crown(m,20,18,16,9,rp,cl=2.0)
    return t.img()

def conifer(seed,rp,bark=REDBARK,tiers=6,width=14,top=6,trunk_show=4,droop=1.5,spire=True):
    t=Tree(seed); r=t.r
    base=GROUND-trunk_show
    t.trunk(20,top+8,3,bark,roots=2,flare=0.8)
    H=base-top
    def m(x,y):
        if y<top or y>base: return False
        u=(y-top)/H
        tier=u*tiers; f=tier%1.0
        # each tier spreads out and droops at its hem
        hw=width*(0.18+0.82*u)*(0.55+0.45*f)
        if spire and y<top+3: hw=max(hw,1)
        jag=((x*13+y*7)%5)/5.0
        return abs(x+0.5-20)<=hw+jag*0.8-(droop*0 )
    t.crown(m,20,top+H*0.55,width,H*0.6,rp,cl=1.7,needle=True,dens=1.1)
    return t.img()

def pinetall(seed,rp=PINE,bark=REDBARK):
    t=Tree(seed); r=t.r
    t.trunk(20,12,3,bark,roots=2,flare=0.7)
    blobs=[(20,12,7,6),(15,20,6,4),(26,21,6,4),(19,27,8,4),(24,32,5,3),(14,31,5,3)]
    m=lambda x,y: any(((x+0.5-bx)/bw)**2+((y+0.5-by)/bh)**2<=1 for bx,by,bw,bh in blobs)
    t.crown(m,20,22,10,13,rp,cl=1.8,needle=True)
    return t.img()

def birch(seed,rp=BIRCHL):
    t=Tree(seed); r=t.r
    # one slender white trunk, a slight lean, black marks across it
    for y in range(18,GROUND+1):
        x=19+0.4*(GROUND-y)*0.08
        for d in range(4): t.px(x+d,y,BIRCH[4 if d==0 else 3 if d==1 else 2 if d==2 else 1])
        if (y*5)%7==0: t.px(x+1,y,BIRCH[0]); t.px(x+2,y,BIRCH[0])
        if (y*3)%11==0: t.px(x,y,BIRCH[1])
    for x in range(17,25): t.px(x,GROUND,BIRCH[1])
    m=lumps(20,21,13,14,r,5)
    t.crown(m,20,21,13,14,rp,cl=2.5)
    return t.img()

def sheet():
    trees=[
        ('oak',lambda: broadleaf(11,OAK)),('oak',lambda: broadleaf(12,OAK,wide=1.1)),('oak',lambda: broadleaf(13,OAK,tall=True)),
        ('lime',lambda: broadleaf(21,LIME)),('lime',lambda: broadleaf(22,LIME,tall=True,wide=0.9)),
        ('twisted',lambda: twisted(31)),('twisted',lambda: twisted(32,rp=LIME)),
        ('spruce',lambda: conifer(41,SPRUCE,tiers=6,width=13,top=4)),('spruce',lambda: conifer(42,SPRUCE,tiers=7,width=15,top=3,trunk_show=3)),
        ('fir',lambda: conifer(51,FIR,tiers=5,width=11,top=8,trunk_show=6)),('fir',lambda: conifer(52,FIR,tiers=6,width=12,top=6,trunk_show=5)),
        ('pine',lambda: pinetall(61)),
        ('autumn',lambda: broadleaf(71,AUTUMN)),('birch',lambda: birch(81)),('blossom',lambda: broadleaf(91,BLOSSOM,wide=1.05)),('oak',lambda: broadleaf(14,OAK,wide=0.95,trunkw=6,roots=4)),
    ]
    ims=[(n,f()) for n,f in trees]
    sh=Image.new('RGBA',(CW*len(ims),CH),(0,0,0,0))
    for i,(n,im) in enumerate(ims): sh.alpha_composite(im,(i*CW,0))
    return sh,ims
if __name__=='__main__':
    sh,ims=sheet(); sh.save('wild_trees.png')
    bg=Image.new('RGBA',(sh.width,sh.height),(200,210,170,255)); bg.alpha_composite(sh); bg.resize((sh.width*3,sh.height*3),Image.NEAREST).save('preview.png')
    print('ok', [n for n,_ in ims])

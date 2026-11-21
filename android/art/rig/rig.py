"""A small 2D rig renderer for chunky, cel-shaded pixel monsters.
Parts are ellipses, capsules (limbs) and polygons in a 100-unit-tall space, rendered at any size:
light from the upper left, banded shading, tufted fur silhouettes, dark outlines, and
darkened inner edges where a part lies over another."""
import math
from PIL import Image
L=(-0.5,-0.7,0.5); _n=math.sqrt(sum(c*c for c in L)); L=tuple(c/_n for c in L)
def _h(a,b):
    v=(a*73856093)^(b*19349663); v^=v>>13; v=(v*0x5bd1e995)&0xFFFFFFFF; v^=v>>15
    return (v&0xFFFF)/65535.0
def _furnoise(x,y,sc):
    # strands about 1 px wide and 2-4 long, rows offset like hair falling in layers
    L=max(2,int(round(sc*2.2)))
    row=y//L; off=int(_h(row,7)*3)
    return _h((x+off)//1,row)*0.75+_h(x//2,y//max(1,L//2))*0.25
def spike(a,n,ph=0.0):
    # a tuft pattern: sharp points, n per turn
    u=(a*n/(2*math.pi)+ph)%1.0
    return 1-abs(u-0.5)*2   # 0..1 triangle
class Rig:
    def __init__(s): s.parts=[]; s.feats=[]
    def ell(s,cx,cy,rx,ry,ramp,layer,fur=0.0,furn=14,edge=False,flat=None,rot=0.0,tex=None):
        s.parts.append(dict(t='e',cx=cx,cy=cy,rx=rx,ry=ry,ramp=ramp,layer=layer,fur=fur,furn=furn,edge=edge,flat=flat,rot=rot,tex=tex if tex is not None else ('fur' if fur>0 else None)))
    def cap(s,x0,y0,r0,x1,y1,r1,ramp,layer,fur=0.0,edge=True,flat=None,tex=None):
        s.parts.append(dict(t='c',x0=x0,y0=y0,r0=r0,x1=x1,y1=y1,r1=r1,ramp=ramp,layer=layer,fur=fur,edge=edge,flat=flat,tex=tex if tex is not None else ('fur' if fur>0 else None)))
    def poly(s,pts,ramp,layer,edge=True,flat=None,grad=(0,1),tex=None):
        s.parts.append(dict(t='p',pts=pts,ramp=ramp,layer=layer,edge=edge,flat=flat,grad=grad,tex=tex))
    def feat(s,fn): s.feats.append(fn)
    def hit(s,p,X,Y):
        if p['t']=='e':
            dx=X-p['cx']; dy=Y-p['cy']
            if p['rot']:
                c=math.cos(-p['rot']); sn=math.sin(-p['rot']); dx,dy=dx*c-dy*sn,dx*sn+dy*c
            a=math.atan2(dy,dx)
            k=1+p['fur']*spike(a,p['furn'],p['cx']*0.13)
            nx=dx/(p['rx']*k); ny=dy/(p['ry']*k); d=nx*nx+ny*ny
            if d>1: return None
            # normal from the plain ellipse
            ex=dx/p['rx']; ey=dy/p['ry']; dd=min(1,ex*ex+ey*ey)
            if p['rot']:
                c=math.cos(p['rot']); sn=math.sin(p['rot']); ex,ey=ex*c-ey*sn,ex*sn+ey*c
            return (ex*0.85,ey*0.85,math.sqrt(max(0,1-dd)))
        if p['t']=='c':
            x0,y0,x1,y1=p['x0'],p['y0'],p['x1'],p['y1']
            vx=x1-x0; vy=y1-y0; ll=vx*vx+vy*vy or 1e-6
            t=max(0,min(1,((X-x0)*vx+(Y-y0)*vy)/ll))
            px=x0+vx*t; py=y0+vy*t; r=p['r0']+(p['r1']-p['r0'])*t
            dx=X-px; dy=Y-py; d=math.hypot(dx,dy)
            if p['fur']:
                side=1 if (dx*vy-dy*vx)>0 else -1
                r*=1+p['fur']*spike(t*6.2832+ (0.5 if side>0 else 0),5)
            if d>r: return None
            q=d/max(r,1e-6)
            return (dx/max(d,1e-6)*q*0.9,dy/max(d,1e-6)*q*0.9,math.sqrt(max(0,1-q*q)))
        pts=p['pts']; c=False; j=len(pts)-1
        for i in range(len(pts)):
            xi,yi=pts[i]; xj,yj=pts[j]
            if (yi>Y)!=(yj>Y) and X<(xj-xi)*(Y-yi)/(yj-yi)+xi: c=not c
            j=i
        if not c: return None
        ys=[q[1] for q in pts]; t=(Y-min(ys))/max(1e-6,max(ys)-min(ys))
        g0,g1=p['grad']; v=g0+(g1-g0)*t
        return (-0.3,-0.6+v*1.2,0.5)
    def render(s,H,W=None,ox=0.0,oy=0.0,scale=None,bands=(-0.15,0.28,0.68,0.9)):
        sc=scale or H/100.0
        W=W or H
        grid=[[None]*W for _ in range(H)]
        for y in range(H):
            for x in range(W):
                X=(x+0.5)/sc+ox; Y=(y+0.5)/sc+oy
                best=None
                for i,p in enumerate(s.parts):
                    if best is not None and s.parts[best[0]]['layer']>p['layer']: continue
                    n=s.hit(p,X,Y)
                    if n is not None: best=(i,n)
                if best is None: continue
                i,n=best; p=s.parts[i]; ramp=p['ramp']
                if p['flat'] is not None: idx=p['flat']
                else:
                    l=n[0]*L[0]+n[1]*L[1]+n[2]*L[2]
                    idx=sum(1 for b in bands if l>b)
                    idx=min(idx,len(ramp)-1)
                    if p.get('tex')=='fur':
                        # fur: tufts and strands, darker in the clefts and catching light on the tips
                        fv=_furnoise(x,y,sc)
                        if fv<0.22: idx=max(0,idx-1)
                        elif fv>0.86 and idx<len(ramp)-1 and l>0.1: idx+=1
                grid[y][x]=(ramp[idx],p['layer'],i,ramp)
        out=[[None]*W for _ in range(H)]
        for y in range(H):
            for x in range(W):
                if grid[y][x]: out[y][x]=grid[y][x][0]
        # inner edges: a part's border against a part behind it takes its own darkest tone
        for y in range(H):
            for x in range(W):
                g=grid[y][x]
                if not g or not s.parts[g[2]].get('edge'): continue
                for dx,dy in((1,0),(-1,0),(0,1),(0,-1)):
                    xx=x+dx; yy=y+dy
                    if 0<=xx<W and 0<=yy<H:
                        o=grid[yy][xx]
                        if o and o[2]!=g[2] and o[1]<g[1] and o[3]!=g[3]:
                            out[y][x]=g[3][0]; break
        for fn in s.feats: fn(out,sc,ox,oy)
        # the outer outline
        q=[r[:] for r in out]
        for y in range(H):
            for x in range(W):
                if q[y][x] is None and any(0<=x+dx<W and 0<=y+dy<H and q[y+dy][x+dx] is not None for dx,dy in((1,0),(-1,0),(0,1),(0,-1))):
                    out[y][x]=OUT
        return out
OUT=(22,14,20)
def to_img(g):
    H=len(g); W=len(g[0]); im=Image.new('RGBA',(W,H),(0,0,0,0))
    for y in range(H):
        for x in range(W):
            if g[y][x] is not None: im.putpixel((x,y),tuple(g[y][x])+(255,))
    return im
def P(g,sc,ox,oy,X,Y):
    return int((X-ox)*sc), int((Y-oy)*sc)
def dot(g,x,y,c):
    if 0<=y<len(g) and 0<=x<len(g[0]): g[y][x]=c
def fill_ell(g,sc,ox,oy,cx,cy,rx,ry,c,minpx=0.5):
    x0,y0=P(g,sc,ox,oy,cx,cy); rxp=max(minpx,rx*sc); ryp=max(minpx,ry*sc)
    for y in range(int(y0-ryp-1),int(y0+ryp+2)):
        for x in range(int(x0-rxp-1),int(x0+rxp+2)):
            if ((x+0.5-(cx-ox)*sc)/rxp)**2+((y+0.5-(cy-oy)*sc)/ryp)**2<=1: dot(g,x,y,c)
def line(g,sc,ox,oy,xa,ya,xb,yb,c,w=1):
    ax,ay=(xa-ox)*sc,(ya-oy)*sc; bx,by=(xb-ox)*sc,(yb-oy)*sc
    n=int(max(abs(bx-ax),abs(by-ay))*2)+1
    for i in range(n+1):
        t=i/n; x=ax+(bx-ax)*t; y=ay+(by-ay)*t
        for dx in range(w):
            for dy in range(w): dot(g,int(x+dx-(w-1)/2),int(y+dy-(w-1)/2),c)

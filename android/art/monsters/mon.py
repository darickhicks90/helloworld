import math
from PIL import Image
L=(-0.55,-0.65,0.52); n=math.sqrt(sum(c*c for c in L)); L=tuple(c/n for c in L)
# palette letters: each ramp has 4 letters dark->light; outline 'K'
class S:
    def __init__(s,w,h): s.w=w; s.h=h; s.prims=[]; s.feat=[]
    def blob(s,cx,cy,rx,ry,ramp,layer=0,flat=False):
        s.prims.append(('e',cx,cy,rx,ry,ramp,layer,flat))
    def tri(s,pts,ramp,layer=0):  # a polygon (horn, claw, spike), shaded by its own gradient
        s.prims.append(('p',pts,ramp,layer))
    def px(s,x,y,c): s.feat.append((x,y,c))
    def render(s):
        g=[[None]*s.w for _ in range(s.h)]  # (char, layer, partid)
        for pid,pr in enumerate(s.prims):
            for y in range(s.h):
                for x in range(s.w):
                    X=x+0.5; Y=y+0.5
                    if pr[0]=='e':
                        _,cx,cy,rx,ry,ramp,layer,flat=pr
                        dx=(X-cx)/rx; dy=(Y-cy)/ry; d=dx*dx+dy*dy
                        if d>1: continue
                        nz=math.sqrt(max(0,1-d)); l=dx*L[0]+dy*L[1]+nz*L[2]
                        if flat: l=0.4
                    else:
                        _,pts,ramp,layer=pr
                        if not inpoly(pts,X,Y): continue
                        # gradient: lighter toward upper-left
                        l=0.55-0.04*(Y-pts[0][1])
                    cur=g[y][x]
                    if cur is not None and cur[1]>layer: continue
                    i=0 if l<0.05 else 1 if l<0.45 else 2 if l<0.82 else 3
                    g[y][x]=(ramp[i],layer,pid,ramp)
        out=[['.']*s.w for _ in range(s.h)]
        for y in range(s.h):
            for x in range(s.w):
                if g[y][x]: out[y][x]=g[y][x][0]
        # sel-out: a part's edge against a part in front of it goes dark
        for y in range(s.h):
            for x in range(s.w):
                c=g[y][x]
                if not c: continue
                for dx,dy in((1,0),(-1,0),(0,1),(0,-1)):
                    q=(x+dx,y+dy)
                    if 0<=q[0]<s.w and 0<=q[1]<s.h:
                        o=g[q[1]][q[0]]
                        if o and o[2]!=c[2] and o[1]>c[1]: out[y][x]=c[3][0]; break
        for x,y,c in s.feat:
            if 0<=x<s.w and 0<=y<s.h: out[y][x]=c
        # the outer outline
        q=[r[:] for r in out]
        for y in range(s.h):
            for x in range(s.w):
                if q[y][x]=='.' and any(0<=x+dx<s.w and 0<=y+dy<s.h and q[y+dy][x+dx]!='.' for dx,dy in((1,0),(-1,0),(0,1),(0,-1))): out[y][x]='K'
        return [''.join(r) for r in out]
def inpoly(pts,x,y):
    c=False; j=len(pts)-1
    for i in range(len(pts)):
        xi,yi=pts[i]; xj,yj=pts[j]
        if (yi>y)!=(yj>y) and x<(xj-xi)*(y-yi)/(yj-yi)+xi: c=not c
        j=i
    return c
def eye(s,x,y,look=1,big=True,angry=False,glow=None):
    # sclera 2x3 (big) with a pupil and a highlight
    if glow:
        s.px(x,y,glow); s.px(x+1,y,glow); s.px(x,y+1,glow); s.px(x+1,y+1,glow); s.px(x,y,'W'); return
    for yy in range(3 if big else 2):
        for xx in range(2): s.px(x+xx,y+yy,'W')
    s.px(x+(1 if look>0 else 0),y+1,'K'); s.px(x+(1 if look>0 else 0),y+2 if big else y+1,'K')
    s.px(x+(0 if look>0 else 1),y,'W')
    if angry: s.px(x+(0 if look>0 else 1),y-1,'K'); s.px(x+(1 if look>0 else 0),y-1,'K')

# ramps
IMP='vpqP'      # violet: dark, mid, light, highlight
WING='mnoO'
HORN='bcdD'
BELLY='fghG'
def imp(frame):
    s=S(16,16)
    hop=[0,-2][frame]; sq=[1.0,0.9][frame]
    # tiny bat wings behind
    s.tri([(3,7+hop),(0,4+hop),(1,9+hop),(4,10+hop)],WING,0); s.tri([(13,7+hop),(16,4+hop),(15,9+hop),(12,10+hop)],WING,0)
    # feet
    s.blob(5.5,14.6,2.2,1.4,IMP,1); s.blob(10.5,14.6,2.2,1.4,IMP,1)
    s.px(3,15,'D'); s.px(12,15,'D')
    # round body-head (one big ball, DWM-style)
    s.blob(8,9.2+hop,6.2/sq*1.0,5.6*sq,IMP,2)
    s.blob(8,13+hop,3.2,1.6,BELLY,3,flat=False)
    # horns
    s.tri([(4,5+hop),(3,1+hop),(6,4+hop)],HORN,4); s.tri([(12,5+hop),(13,1+hop),(10,4+hop)],HORN,4)
    # tail tip
    s.tri([(13,12+hop),(15.6,10.5+hop),(15,13+hop)],IMP,1)
    # narrowed, glowing eyes under a hard scowl
    for ex in (5,9):
        s.px(ex,7+hop,'Y'); s.px(ex+1,7+hop,'Y'); s.px(ex,8+hop,'Y'); s.px(ex+1,8+hop,'Y')
        s.px(ex+(1 if ex==5 else 0),8+hop,'X')
    s.px(4,5+hop,'K'); s.px(5,6+hop,'K'); s.px(6,6+hop,'K'); s.px(7,7+hop,'K')
    s.px(11,5+hop,'K'); s.px(10,6+hop,'K'); s.px(9,6+hop,'K'); s.px(8,7+hop,'K')
    # a wide leer full of fangs
    for x in range(5,11): s.px(x,10+hop,'M')
    for x in range(6,10): s.px(x,11+hop,'M')
    s.px(5,10+hop,'W'); s.px(10,10+hop,'W'); s.px(7,10+hop,'W'); s.px(8,11+hop,'W')
    return s.render()
SH='ajeE'   # shadow hound: indigo
MANE='stuU'
def stalker(frame):
    s=S(22,15)
    crouch=frame==2; oy=1 if crouch else 0
    st=[(0,1),(1,0),(0,0)][frame]
    # legs (back pair behind)
    for i,(lx,ph) in enumerate(((6,st[0]),(14,st[1]))):
        s.blob(lx-1.5+ph,12.4,1.6,2.4,SH,0); s.blob(lx+1+(1-ph),12.4,1.6,2.4,SH,3)
    # tail sweeping up
    s.tri([(4,8+oy),(0,3+oy),(1,2+oy),(5,6+oy)],SH,1)
    s.blob(0.8,2.8+oy,1.6,1.6,MANE,1)
    # long round body
    s.blob(10,8.2+oy,7.2,3.8,SH,2)
    # spiky mane
    for k in range(4): s.tri([(7+k*2.5,5+oy),(8+k*2.5,1.5+oy+(k%2)),(10+k*2.5,5+oy)],MANE,2)
    # big round head
    s.blob(17,7+oy,4.6,4.2,SH,4)
    s.tri([(14.5,4+oy),(14,0+oy),(17,3+oy)],MANE,5); s.tri([(18,3.5+oy),(20,0+oy),(20,4+oy)],MANE,5)
    s.blob(20.6,9+oy,1.6,1.3,SH,5)
    # eyes: two big glowing slits
    if crouch: s.px(16,6+oy,'X'); s.px(17,6+oy,'X'); s.px(19,6+oy,'X'); s.px(20,6+oy,'X')
    else:
        s.px(16,6+oy,'Y'); s.px(16,7+oy,'X'); s.px(17,6+oy,'W'); s.px(17,7+oy,'Y'); s.px(19,6+oy,'Y'); s.px(19,7+oy,'X'); s.px(20,6+oy,'W')
    for x in (18,19,20,21): s.px(x,9+oy,'M')
    s.px(18,9+oy,'W'); s.px(20,9+oy,'W'); s.px(19,10+oy,'W'); s.px(21,10+oy,'W')
    s.px(15,5+oy,'K'); s.px(16,5+oy,'K'); s.px(18,5+oy,'K'); s.px(19,5+oy,'K')
    return s.render()
BH='RrhH'   # red
TUSK='bcdD'
BB='fghG'
def behemoth(frame):
    s=S(32,32)
    walk=frame in(0,1); slam=frame==2; daze=frame==3
    sy=3 if daze else 0
    step=[1,-1,0,0][frame]
    # legs: stubby, wide
    s.blob(10,26.5+sy-(step>0),3.8,4.4,BH,1); s.blob(22,26.5+sy-(step<0),3.8,4.4,BH,1)
    s.blob(9.5,30.2,4,1.6,TUSK,2,); s.blob(22.5,30.2,4,1.6,TUSK,2)
    # tail
    s.tri([(25,22+sy),(31,24+sy),(30,20+sy),(27,20+sy)],BH,0); s.tri([(29,19+sy),(32,17+sy),(31.6,21+sy)],BH,0)
    # the great round body
    s.blob(16,18+sy,11.5,10,BH,3)
    s.blob(16,22+sy,7,5.5,BB,4)
    # arms
    for side in(-1,1):
        if slam:
            s.blob(16+side*11,9,3.4,6,BH,5); s.blob(16+side*12,3.5,3.6,3.2,BH,6)
            for k in(-1,0,1): s.px(16+side*12+k*2,0,'D')
        elif daze:
            s.blob(16+side*11,24+sy,3.4,5,BH,5); s.blob(16+side*11.5,29,3.2,2.4,BH,6)
        else:
            s.blob(16+side*11.5,19+sy+side*step,3.6,6,BH,5); s.blob(16+side*12,25+sy+side*step,3.6,3,BH,6)
            for k in(-1,0,1): s.px(int(16+side*12+k*2),28+sy+side*step,'D')
    # head sunk between shoulders
    hy=11+sy+(2 if daze else 0)
    s.blob(16,hy,7.6,6.4,BH,7)
    # horns: big, curling up
    for side in(-1,1):
        s.tri([(16+side*5,hy-3),(16+side*11,hy-6),(16+side*13,hy-12),(16+side*10,hy-8),(16+side*4,hy-6)],TUSK,8)
    # brow and eyes
    if daze:
        for ex in(11,19):
            s.px(ex,hy-1,'K'); s.px(ex+1,hy,'K'); s.px(ex+2,hy-1,'K')
    else:
        for ex,lk in((11,1),(18,0)):
            for yy in range(2):
                for xx in range(3): s.px(ex+xx,hy-1+yy,'Y')
            s.px(ex+1+lk*0,hy,'K'); s.px(ex+2 if lk else ex,hy-1,'W')
        # a heavy scowl, sloping down to the middle
        for k in range(4): s.px(10+k,hy-4+k//2,'K'); s.px(22-k,hy-4+k//2,'K')
    # maw with tusks
    for xx in range(11,22): s.px(xx,hy+3,'M')
    for xx in range(12,21): s.px(xx,hy+4,'M')
    for xx in range(13,20): s.px(xx,hy+5,'M')
    for xx in range(13,20,2): s.px(xx,hy+3,'W')
    for xx in range(14,20,2): s.px(xx,hy+5,'W')
    s.px(12,hy+2,'D'); s.px(12,hy+1,'D'); s.px(20,hy+2,'D'); s.px(20,hy+1,'D'); s.px(14,hy+3,'W'); s.px(18,hy+3,'W')
    return s.render()

EYEB='wxyz'
def gazer(frame):
    s=S(16,16)
    up=frame==0
    # leathery wings, up or down
    if up:
        s.tri([(5,6),(0.5,0),(1.5,3),(0,5),(1.2,7),(0,9.5),(4,10)],WING,0); s.tri([(11,6),(15.5,0),(14.5,3),(16,5),(14.8,7),(16,9.5),(12,10)],WING,0)
    else:
        s.tri([(5,9),(0.5,15),(1.5,12),(0,10),(1.2,8),(0,5.5),(4,5)],WING,0); s.tri([(11,9),(15.5,15),(14.5,12),(16,10),(14.8,8),(16,5.5),(12,5)],WING,0)
    # the great eyeball
    s.blob(8,7.5,5,5,EYEB,2)
    # iris and a slit pupil, glaring
    for (x,y) in [(7,6),(8,6),(9,6),(7,7),(9,7),(7,8),(8,8),(9,8),(6,7),(10,7)]: s.px(x,y,'i')
    s.px(8,7,'K'); s.px(8,6,'K'); s.px(8,8,'K'); s.px(7,6,'I')
    # a heavy lid scowling down
    for x in range(5,12): s.px(x,3 if x in (5,11) else 4 if 7<=x<=9 else 3,'K')
    # veins
    s.px(4,9,'r'); s.px(5,10,'r'); s.px(12,9,'r')
    # a fanged slit of a mouth underneath
    for x in range(6,11): s.px(x,12,'M')
    s.px(6,12,'W'); s.px(8,12,'W'); s.px(10,12,'W'); s.px(7,13,'W'); s.px(9,13,'W')
    return s.render()
PAL={'K':(28,20,40),'W':(255,255,255),'r':(220,60,80),'M':(60,10,24),'X':(255,70,70),'Y':(255,236,110),
 'v':(70,24,70),'p':(128,40,96),'q':(180,70,120),'P':(230,140,170),
 'm':(48,20,40),'n':(84,36,64),'o':(120,56,88),'O':(170,96,120),
 'b':(150,120,80),'c':(210,184,136),'d':(240,226,190),'D':(255,250,232),
 'f':(140,60,70),'g':(196,100,104),'h':(236,150,140),'G':(255,206,190),
 'a':(30,30,64),'j':(42,44,92),'e':(56,58,110),'E':(110,118,190),'s':(20,60,80),'t':(30,110,130),'u':(60,170,180),'U':(160,240,240),
 'R':(90,12,28),'H':(240,120,96),'w':(150,132,150),'x':(206,192,204),'y':(238,230,236),'z':(255,252,250),'i':(200,36,40),'I':(255,140,70)}
PAL['r']=(176,34,46)
# note: 'h'/'H' in behemoth ramp: R dark, r mid, h light, H highlight; reuse belly 'h' fine
def preview(frames,scale,name):
    w=len(frames[0][0]); h=len(frames[0])
    im=Image.new('RGB',(w*len(frames)*scale+8*len(frames),h*scale),(236,240,236))
    for i,f in enumerate(frames):
        for y,row in enumerate(f):
            for x,ch in enumerate(row):
                if ch!='.':
                    for a in range(scale):
                        for b in range(scale): im.putpixel((i*(w*scale+8)+x*scale+a,y*scale+b),PAL[ch])
    im.save(name)
Z=[gazer(i) for i in range(2)]; preview(Z,10,'gazer.png')
I=[imp(i) for i in range(2)]; T=[stalker(i) for i in range(3)]; B=[behemoth(i) for i in range(4)]
preview(I,10,'imp.png'); preview(T,8,'stalker.png'); preview(B,6,'behemoth.png')
import json; json.dump({'imp':I,'stalker':T,'behemoth':B,'gazer':Z},open('rows.json','w'))
print('ok')

from PIL import Image
import json
PAL={'K':(26,20,30),'R':(200,40,50),'r':(132,22,38),'h':(240,104,96),'S':(244,190,142),'s':(206,140,102),'p':(236,140,130),
'H':(112,64,32),'J':(150,92,46),'E':(34,26,44),'W':(250,250,246),
'G':(62,146,72),'g':(30,84,46),'L':(150,206,120),'T':(236,236,228),
'B':(132,86,50),'b':(86,54,30),'O':(62,40,30),'o':(120,86,60),'Y':(232,192,72),
'C':(226,168,52),'c':(170,112,30),'V':(120,78,40),'v':(84,52,26)}
W,H=20,30
def blank(): return [['.']*W for _ in range(H)]
def P(g,x,y,c):
    if 0<=x<W and 0<=y<H: g[y][x]=c
def R(g,l,t,r,b,c):
    for y in range(t,b+1):
        for x in range(l,r+1): P(g,x,y,c)
def plaid(g,l,t,r,b,ox=0):
    for y in range(t,b+1):
        for x in range(l,r+1):
            v=(x+ox)%4==0; hz=(y-t)%3==1
            P(g,x,y,'K' if v and hz else 'g' if v or hz else 'L' if ((x+ox)%4==2 and (y-t)%3==0) else 'G')
def outline(g):
    q=[r[:] for r in g]
    for y in range(H):
        for x in range(W):
            if q[y][x]=='.' and any(0<=x+dx<W and 0<=y+dy<H and q[y+dy][x+dx]!='.' for dx,dy in((1,0),(-1,0),(0,1),(0,-1))): g[y][x]='K'
    return g
def legs_front(g,step):
    a=1 if step else 0
    R(g,5,23,9,25-a,'B'); R(g,10,23,14,25+a,'B'); P(g,9,23,'b'); P(g,10,23,'b'); P(g,6,24-a,'b'); P(g,13,24+a,'b')
    R(g,4,26-a,9,28-a,'O'); R(g,10,26+a,15,min(29,28+a),'O'); R(g,4,26-a,9,26-a,'o'); R(g,10,26+a,15,26+a,'o')
def front(step=False,breathe=False,blink=False,flutter=False):
    g=blank(); d=1 if breathe else 0
    # tiny beanie, messy hair poking out
    R(g,7,3+d,12,4+d,'R'); R(g,6,5+d,13,5+d,'r'); P(g,8,3+d,'h'); P(g,9,2+d,'h'); P(g,10,2+d,'R')
    R(g,5,6+d,14,8+d,'H'); P(g,4,7+d,'H'); P(g,15,7+d,'H'); P(g,4,8+d,'J'); P(g,15,9+d,'H'); P(g,3,8+d,'H'); P(g,16,8+d,'J')
    # face
    R(g,6,8+d,13,13+d,'S'); P(g,6,8+d,'H'); P(g,8,8+d,'H'); P(g,11,8+d,'J'); P(g,13,8+d,'H')
    if blink: R(g,7,10+d,8,10+d,'E'); R(g,11,10+d,12,10+d,'E')
    else:
        R(g,7,10+d,8,11+d,'E'); R(g,11,10+d,12,11+d,'E'); P(g,7,10+d,'W'); P(g,11,10+d,'W')
    P(g,7,9+d,'H'); P(g,8,9+d,'H'); P(g,11,9+d,'H'); P(g,12,9+d,'H')
    P(g,10,12+d,'s'); P(g,6,12+d,'p'); P(g,13,12+d,'p')
    P(g,9,13+d,'E'); P(g,10,13+d,'E'); P(g,11,12+d,'E')
    R(g,7,14+d,12,14+d,'s')
    # scarf, its end hanging (fluttering)
    R(g,5,15,14,16,'C'); P(g,5,16,'c'); P(g,14,16,'c')
    ex=1 if flutter else 0
    R(g,12+ex,17,13+ex,20,'C'); P(g,13+ex,20,'c'); P(g,12+ex,21,'c')
    # flannel over a tee, a satchel strap across
    plaid(g,4,17,15,22); R(g,9,17,10,22,'T')
    for k in range(6): P(g,5+k*2,17+k,'V'); P(g,6+k*2,17+k,'V')
    R(g,1,20,4,23,'V'); R(g,1,20,4,20,'v'); P(g,2,22,'Y')
    # arms with rolled cuffs, hands
    R(g,3,17,3,21,'G'); R(g,16,17,16,21,'G'); P(g,3,21,'L'); P(g,16,21,'L'); P(g,3,22,'S'); P(g,16,22,'S')
    R(g,5,22,14,22,'b'); P(g,9,22,'Y'); P(g,10,22,'Y')
    legs_front(g,step); return outline(g)
def back(step=False,breathe=False):
    g=blank(); d=1 if breathe else 0
    R(g,7,3+d,12,4+d,'R'); R(g,6,5+d,13,5+d,'r'); P(g,9,2+d,'h'); P(g,10,2+d,'R')
    R(g,5,6+d,14,13+d,'H'); P(g,4,8+d,'H'); P(g,15,8+d,'H'); P(g,4,9+d,'J'); P(g,15,10+d,'J')
    for x in range(6,14,2): P(g,x,12+d,'J')
    R(g,5,15,14,16,'C'); R(g,9,17,10,19,'C'); P(g,10,19,'c')
    plaid(g,4,17,15,22,ox=2)
    for k in range(6): P(g,14-k*2,17+k,'V'); P(g,13-k*2,17+k,'V')
    R(g,3,17,3,21,'G'); R(g,16,17,16,21,'G'); P(g,3,22,'S'); P(g,16,22,'S')
    R(g,5,22,14,22,'b')
    legs_front(g,step); return outline(g)
def side(step=False,breathe=False,blink=False):
    # facing left
    g=blank(); d=1 if breathe else 0
    R(g,6,3+d,11,4+d,'R'); R(g,5,5+d,12,5+d,'r'); P(g,12,3+d,'h'); P(g,13,2+d,'h')
    R(g,8,6+d,13,12+d,'H'); P(g,14,8+d,'H'); P(g,14,10+d,'J'); P(g,13,13+d,'H')
    R(g,4,7+d,9,13+d,'S'); P(g,3,10+d,'S'); P(g,3,11+d,'s')
    P(g,5,6+d,'H'); P(g,7,6+d,'H'); P(g,4,7+d,'H')
    if blink: P(g,5,10+d,'E'); P(g,6,10+d,'E')
    else: R(g,5,9+d,6,10+d,'E'); P(g,5,9+d,'W')
    P(g,5,8+d,'H'); P(g,6,8+d,'H'); P(g,8,11+d,'p'); P(g,4,12+d,'E'); P(g,5,12+d,'E')
    R(g,5,14+d,9,14+d,'s')
    # scarf with its end streaming behind
    R(g,5,15,12,16,'C'); R(g,12,16,15,17,'C'); P(g,15,18,'c'); P(g,16,17,'c')
    plaid(g,6,17,13,22,ox=1); R(g,6,17,7,19,'T')
    R(g,13,19,15,23,'V'); R(g,13,19,15,19,'v')
    if step: R(g,6,17,7,20,'G'); P(g,5,21,'S'); P(g,6,21,'L')
    else: R(g,9,17,10,21,'G'); P(g,9,22,'S'); P(g,10,21,'L')
    R(g,6,22,13,22,'b')
    if step: R(g,6,23,8,25,'B'); R(g,10,23,12,25,'b'); R(g,4,26,8,28,'O'); R(g,11,26,15,28,'O'); R(g,4,26,8,26,'o')
    else: R(g,7,23,11,25,'B'); P(g,11,25,'b'); R(g,6,26,11,28,'O'); R(g,6,26,11,26,'o')
    return outline(g)
F={'down':[front(),front(step=True)],'up':[back(),back(step=True)],'side':[side(),side(step=True)],
   'idleDown':[front(),front(breathe=True),front(blink=True),front(flutter=True)],'idleSide':[side(),side(breathe=True),side(blink=True)],'idleUp':[back(),back(breathe=True)]}
json.dump({k:[[''.join(r) for r in f] for f in v] for k,v in F.items()},open('hero3.json','w'))
S8=8; allf=F['idleDown']+F['down']+F['side']+F['idleSide']+F['up']
im=Image.new('RGB',(len(allf)*(W*S8+6),H*S8),(120,170,90))
for i,f in enumerate(allf):
    for y,row in enumerate(f):
        for x,ch in enumerate(row):
            if ch!='.':
                for a in range(S8):
                    for b in range(S8): im.putpixel((i*(W*S8+6)+x*S8+a,y*S8+b),PAL[ch])
im.save('hero3.png'); print('ok')

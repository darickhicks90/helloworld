from PIL import Image
PAL={'K':(26,20,30),'R':(200,40,50),'r':(132,22,38),'h':(240,104,96),'S':(244,190,142),'s':(206,140,102),'H':(112,64,32),'E':(34,26,44),
'G':(62,146,72),'g':(30,84,46),'L':(150,206,120),'W':(244,244,240),'B':(132,86,50),'b':(86,54,30),'O':(62,40,30),'o':(110,80,56),'Y':(232,192,72)}
W,H=20,30
KS=1.25
def blank(): return [['.']*W for _ in range(H)]
def _p(g,x,y,c):
    if 0<=x<W and 0<=y<H: g[y][x]=c
def rect(g,l,t,r,b,c):
    # drawn in the 16 x 24 design's units, rasterised fresh at 1.25x (no doubled pixels)
    for y in range(round(t*KS),max(round(t*KS)+1,round((b+1)*KS))):
        for x in range(round(l*KS),max(round(l*KS)+1,round((r+1)*KS))): _p(g,x,y,c)
def p(g,x,y,c): rect(g,x,y,x,y,c)
def plaid(g,l,t,r,b,ox=0):
    l=round(l*KS); t=round(t*KS); r=round((r+1)*KS)-1; b=round((b+1)*KS)-1
    for y in range(t,b+1):
        for x in range(l,r+1):
            c='G'
            v=(x+ox)%4==0; hz=(y-t)%3==1
            if v and hz: c='K'
            elif v or hz: c='g'
            elif (x+ox)%4==2 and (y-t)%3==0: c='L'
            _p(g,x,y,c)
def outline(g):
    q=[r[:] for r in g]
    for y in range(H):
        for x in range(W):
            if q[y][x]=='.' and any(0<=x+dx<W and 0<=y+dy<H and q[y+dy][x+dx] not in '.' for dx,dy in((1,0),(-1,0),(0,1),(0,-1))): g[y][x]='K'
    return g
def legs(g,step,side=False):
    if not side:
        a=1 if step else 0
        rect(g,4,19,7,20-a,'B'); rect(g,8,19,11,20+a,'B'); p(g,7,19,'b'); p(g,8,19,'b')
        for x in range(4,8): p(g,x,20-a,'b')
        rect(g,4,21-a,7,22-a,'O'); rect(g,8,21+a,11,22+a if 22+a<H else 23,'O'); p(g,4,21-a,'o'); p(g,8,21+a,'o')
    else:
        if step: rect(g,5,19,7,20,'B'); rect(g,8,19,10,20,'b'); rect(g,3,21,6,22,'O'); rect(g,9,21,12,22,'O'); p(g,3,21,'o')
        else: rect(g,6,19,9,20,'B'); p(g,9,20,'b'); rect(g,5,21,9,22,'O'); p(g,5,21,'o')
def beanie(g,back=False,side=False):
    # pom, dome, ribbed cuff
    if side: rect(g,10,1,11,2,'h'); p(g,11,1,'W')
    else: rect(g,6,0,9,1,'h'); p(g,7,0,'W'); p(g,6,0,'.'); p(g,9,0,'.')
    rect(g,4,2,11,3,'R'); rect(g,3,4,12,5,'R'); p(g,5,2,'h'); p(g,4,4,'h'); p(g,5,3,'h')
    for x in range(3,13): p(g,x,6,'r'); p(g,x,7,'r' if x%2 else 'R')
    p(g,12,5,'r'); p(g,11,2,'r')
def front(step):
    g=blank(); beanie(g)
    rect(g,4,8,11,12,'S'); p(g,3,8,'H'); p(g,12,8,'H'); p(g,3,9,'H'); p(g,12,9,'H')
    p(g,6,9,'E'); p(g,6,10,'E'); p(g,9,9,'E'); p(g,9,10,'E'); p(g,5,11,'s'); p(g,10,11,'s'); p(g,7,11,'s'); p(g,8,11,'s')
    rect(g,4,12,11,12,'s'); rect(g,6,12,9,12,'S')
    plaid(g,3,13,12,18); rect(g,7,13,8,15,'W'); p(g,6,13,'g'); p(g,9,13,'g')
    a=1 if step else 0
    rect(g,2,13,2,17-a,'G'); rect(g,13,13,13,17+a-1,'G'); p(g,2,15,'g'); p(g,13,15,'g')
    p(g,2,18-a,'S'); p(g,13,17+a,'S')
    for x in range(4,12): p(g,x,18,'b')
    p(g,7,18,'Y'); p(g,8,18,'Y')
    legs(g,step); return outline(g)
def back(step):
    g=blank(); beanie(g,back=True)
    rect(g,4,8,11,11,'H'); rect(g,5,12,10,12,'s')
    plaid(g,3,13,12,18,ox=2)
    a=1 if step else 0
    rect(g,2,13,2,17+a-1,'G'); rect(g,13,13,13,17-a,'G'); p(g,2,17+a,'S'); p(g,13,18-a,'S')
    for x in range(4,12): p(g,x,18,'b')
    legs(g,step); return outline(g)
def side(step):
    # facing left
    g=blank(); beanie(g,side=True)
    rect(g,3,8,9,12,'S'); rect(g,9,8,11,11,'H'); p(g,10,12,'H'); p(g,2,10,'S')
    p(g,5,9,'E'); p(g,5,10,'E'); p(g,4,11,'s'); p(g,6,12,'s'); p(g,7,12,'s')
    plaid(g,4,13,10,18,ox=1); p(g,4,13,'W'); p(g,5,13,'W')
    # the near arm, swinging
    if step: rect(g,8,13,9,16,'G'); p(g,9,14,'g'); rect(g,9,17,10,17,'S')
    else: rect(g,7,13,8,17,'G'); p(g,8,15,'g'); p(g,7,18,'S'); p(g,8,18,'S')
    for x in range(4,11): p(g,x,18,'b')
    legs(g,step,side=True); return outline(g)
frames={'down':[front(0),front(1)],'up':[back(0),back(1)],'side':[side(0),side(1)]}
rows={k:[[''.join(r) for r in f] for f in v] for k,v in frames.items()}
import json; json.dump(rows,open('hero.json','w'))
S=8; im=Image.new('RGB',(W*6*S+50,H*S),(120,170,90))
for i,f in enumerate(frames['down']+frames['side']+frames['up']):
    for y,row in enumerate(f):
        for x,ch in enumerate(row):
            if ch!='.':
                for a in range(S):
                    for b in range(S): im.putpixel((i*(W*S+10)+x*S+a,y*S+b),PAL[ch])
im.save('hero.png'); print('ok')

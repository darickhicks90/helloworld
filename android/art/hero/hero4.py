# The hero, 18x28: long hair, a full beard, flannel and a satchel; no hat.
from PIL import Image
import json,sys
PAL={'K':(26,20,30),'S':(244,190,142),'s':(206,140,102),'p':(236,140,130),
'H':(74,42,26),'J':(108,66,36),'j':(150,96,52),'D':(44,26,18),'A':(168,92,42),'a':(120,62,28),'Q':(204,128,64),'E':(34,26,44),'W':(250,250,246),
'G':(62,146,72),'g':(30,84,46),'L':(150,206,120),'T':(236,236,228),
'B':(132,86,50),'b':(86,54,30),'O':(62,40,30),'o':(120,86,60),'Y':(232,192,72),
'V':(120,78,40),'v':(84,52,26),'R':(180,46,46)}
W,H=18,28
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
def strands(g,xs,t,b,sway=0):
    # hair falling in locks, light on the crown, dark at the tips
    for i,x in enumerate(xs):
        for y in range(t,b+1+(i%2)):
            P(g,x+(sway if y>b-2 else 0),y,'J' if y<t+2 else ('D' if y>=b else 'H'))
def legs(g,step,l=4):
    a=1 if step else 0
    R(g,l,21,l+4,23-a,'B'); R(g,l+5,21,l+9,23+a,'B'); P(g,l+4,21,'b'); P(g,l+5,21,'b'); P(g,l+1,22-a,'b'); P(g,l+8,22+a,'b')
    R(g,l-1,24-a,l+4,26-a,'O'); R(g,l+5,24+a,l+10,min(27,26+a),'O'); R(g,l-1,24-a,l+4,24-a,'o'); R(g,l+5,24+a,l+10,24+a,'o')
def front(step=False,breathe=False,blink=False,sway=0):
    g=blank(); d=1 if breathe else 0
    # crown of hair parted in the middle
    R(g,5,2+d,12,3+d,'H'); R(g,6,1+d,11,1+d,'J'); P(g,8,1+d,'j'); P(g,7,2+d,'j'); P(g,10,2+d,'J'); P(g,9,2+d,'D')
    # long hair falling past the shoulders on both sides
    strands(g,[3,4],4+d,15,sway); strands(g,[13,14],4+d,15,sway)
    R(g,4,4+d,13,4+d,'H')
    # face
    R(g,5,5+d,12,9+d,'S'); P(g,5,5+d,'H'); P(g,12,5+d,'H'); P(g,6,5+d,'J'); P(g,11,5+d,'J')
    R(g,6,6+d,7,6+d,'D'); R(g,10,6+d,11,6+d,'D')   # heavy brows
    if blink: R(g,6,7+d,7,7+d,'E'); R(g,10,7+d,11,7+d,'E')
    else: R(g,6,7+d,7,8+d,'E'); R(g,10,7+d,11,8+d,'E'); P(g,6,7+d,'W'); P(g,10,7+d,'W')
    P(g,8,8+d,'s'); P(g,9,8+d,'s'); P(g,5,8+d,'p'); P(g,12,8+d,'p')
    # the beard: full, to the chest, a mouth inside it
    R(g,5,9+d,12,12+d,'A'); R(g,6,13+d,11,13+d,'A'); R(g,7,14+d,10,15,'A'); P(g,8,16,'a'); P(g,9,16,'A')
    R(g,7,10+d,10,10+d,'a'); R(g,8,10+d,9,10+d,'p'); P(g,7,9+d,'Q'); P(g,10,9+d,'Q'); P(g,6,9+d,'S'); P(g,11,9+d,'S')
    for x,y in ((6,12),(9,12),(8,14),(11,11),(5,11),(10,13)): P(g,x,y+d if y<14 else y,'a')
    for x,y in ((7,12),(10,11),(6,10)): P(g,x,y+d,'Q')
    # flannel shirt, tee at the collar, satchel strap
    plaid(g,3,16,14,20); R(g,8,16,9,16,'T')
    R(g,7,15,10,15,'A')
    for k in range(5): P(g,4+k*2,16+k,'V'); P(g,5+k*2,16+k,'V')
    R(g,0,18,3,21,'V'); R(g,0,18,3,18,'v'); P(g,1,20,'Y')
    # arms, rolled cuffs, hands
    R(g,2,16,2,19,'G'); R(g,15,16,15,19,'G'); P(g,2,20,'L'); P(g,15,20,'L'); P(g,2,21,'S'); P(g,15,21,'S')
    R(g,4,20,13,20,'b'); P(g,8,20,'Y'); P(g,9,20,'Y')
    # hair over the shoulders, in front of the flannel
    strands(g,[3],13,17,sway); strands(g,[14],13,17,sway)
    legs(g,step); return outline(g)
def back(step=False,breathe=False,sway=0):
    g=blank(); d=1 if breathe else 0
    R(g,5,2+d,12,3+d,'H'); R(g,6,1+d,11,1+d,'J'); P(g,8,1+d,'j')
    R(g,4,4+d,13,15,'H')
    for x in range(4,14):
        for y in range(5+d,16):
            if (x+y//3)%3==0: P(g,x,y,'J')
            elif (x*7+y)%5==0: P(g,x,y,'D')
    for x in range(4,14,2): P(g,x+sway,16,'D')
    P(g,7,2+d,'j'); P(g,10,3+d,'j')
    plaid(g,3,16,14,20,ox=2); R(g,5,16,12,16,'H')
    for k in range(5): P(g,13-k*2,16+k,'V'); P(g,12-k*2,16+k,'V')
    R(g,2,16,2,19,'G'); R(g,15,16,15,19,'G'); P(g,2,21,'S'); P(g,15,21,'S'); P(g,2,20,'L'); P(g,15,20,'L')
    R(g,4,20,13,20,'b')
    legs(g,step); return outline(g)
def side(step=False,breathe=False,blink=False,sway=0):
    # facing left
    g=blank(); d=1 if breathe else 0
    R(g,5,2+d,11,3+d,'H'); R(g,6,1+d,10,1+d,'J'); P(g,7,1+d,'j'); P(g,6,2+d,'j')
    # long hair down the back
    R(g,9,4+d,13,13,'H'); strands(g,[11,12,13],6+d,16,sway); P(g,10,6+d,'J'); P(g,12,8+d,'J'); P(g,11,11,'D'); P(g,9,8+d,'S'); P(g,9,7+d,'s')
    # face in profile
    R(g,3,4+d,8,8+d,'S'); P(g,2,7+d,'S'); P(g,2,8+d,'s'); R(g,3,4+d,6,4+d,'H')
    R(g,3,5+d,5,5+d,'D')
    if blink: R(g,4,6+d,5,6+d,'E')
    else: R(g,4,6+d,5,7+d,'E'); P(g,4,6+d,'W')
    P(g,7,7+d,'p')
    # beard jutting forward
    R(g,2,9+d,8,11+d,'A'); R(g,3,12+d,7,13+d,'A'); R(g,4,14,6,15,'A'); P(g,5,16,'a')
    P(g,3,9+d,'a'); P(g,2,9+d,'p'); P(g,4,11+d,'Q'); P(g,6,13+d,'a'); P(g,7,10+d,'a'); P(g,5,12+d,'Q'); P(g,8,9+d,'S')
    plaid(g,5,16,12,20,ox=1); R(g,5,16,6,17,'T')
    R(g,12,17,14,21,'V'); R(g,12,17,14,17,'v')
    if step: R(g,5,16,6,19,'G'); P(g,4,20,'S'); P(g,5,20,'L')
    else: R(g,8,16,9,19,'G'); P(g,8,20,'S'); P(g,9,19,'L')
    R(g,5,20,12,20,'b')
    if step: R(g,5,21,7,23,'B'); R(g,9,21,11,23,'b'); R(g,3,24,7,26,'O'); R(g,10,24,14,26,'O'); R(g,3,24,7,24,'o')
    else: R(g,6,21,10,23,'B'); P(g,10,23,'b'); R(g,5,24,10,26,'O'); R(g,5,24,10,24,'o')
    return outline(g)
F={'down':[front(),front(step=True)],'up':[back(),back(step=True)],'side':[side(),side(step=True)],
   'idleDown':[front(),front(breathe=True),front(blink=True),front(sway=1)],'idleUp':[back(),back(breathe=True)],'idleSide':[side(),side(breathe=True),side(blink=True)]}
def kt(f): return 'Sprite(listOf('+', '.join('"'+''.join(r)+'"' for r in f)+'), heroPal, outline = null)'
def arr(fs): return 'arrayOf('+', '.join(kt(f) for f in fs)+')'
pal='mapOf('+', '.join("'%s' to 0xFF%02X%02X%02X.toInt()"%(k,*v) for k,v in PAL.items())+')'
open('hero4.kt','w').write('    private val heroPal = '+pal+'\n'+
 '    private val hero = arrayOf('+', '.join(arr(F[k]) for k in ('down','up','side'))+')\n'+
 '    private val heroIdle = arrayOf('+', '.join(arr(F[k]) for k in ('idleDown','idleUp','idleSide'))+')\n')
S8=8; allf=F['idleDown']+F['down']+F['side']+F['idleSide']+F['up']
im=Image.new('RGB',(len(allf)*(W*S8+6),H*S8),(120,170,90))
for i,f in enumerate(allf):
    for y,row in enumerate(f):
        for x,ch in enumerate(row):
            if ch!='.':
                for a in range(S8):
                    for b in range(S8): im.putpixel((i*(W*S8+6)+x*S8+a,y*S8+b),PAL[ch])
im.save(sys.argv[1] if len(sys.argv)>1 else 'hero4.png'); print('ok')

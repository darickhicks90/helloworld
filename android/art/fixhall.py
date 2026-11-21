from PIL import Image
im=Image.open('hall_blossom.png').convert('RGBA'); px=im.load()
def lum(c): return 0.3*c[0]+0.59*c[1]+0.11*c[2]
OUT=(40,28,48,255)
for f in range(6):
    ox=f*72
    cell=[(x,y) for x in range(ox,ox+72) for y in range(72) if px[x,y][3]>200]
    furs=sorted([px[x,y] for (x,y) in cell if lum(px[x,y])>150 and abs(px[x,y][0]-px[x,y][2])<60], key=lum)
    LIGHT=furs[int(len(furs)*0.8)]; MIDC=furs[int(len(furs)*0.4)]; DARKC=furs[int(len(furs)*0.08)]
    OUT=min((px[x,y] for (x,y) in cell), key=lum)
    # the dagger as it was: gold guard and pale steel, floating by the hip
    gold=[(x,y) for (x,y) in cell if px[x,y][0]>200 and px[x,y][1]>150 and px[x,y][2]<120]
    steel=[(x,y) for (x,y) in cell if px[x,y][2]>px[x,y][0]+15 and y>32 and x<ox+40]
    if not gold: continue
    gx=min(x for x,_ in gold); gy=min(y for _,y in gold)
    for (x,y) in gold+steel:
        # fur back where the old dagger was (what's left of it, then its neighbours)
        px[x,y]=MIDC
    # the fist at the end of her forearm, the dagger gripped in it, guard and blade below
    hx=gx-4; hy=gy-2
    for (x,y) in [(hx,hy),(hx+1,hy),(hx+2,hy),(hx,hy+1),(hx+1,hy+1),(hx+2,hy+1),(hx,hy+2),(hx+1,hy+2),(hx+2,hy+2)]:
        px[x,y]=LIGHT if y==hy else MIDC
    px[hx+3,hy]=OUT; px[hx+3,hy+1]=OUT; px[hx+3,hy+2]=OUT; px[hx,hy+2]=DARKC
    bx=hx+1
    for x in range(hx-1,hx+4): px[x,hy+3]=(232,184,64,255)
    for y in range(hy+4,hy+10): px[bx,y]=(214,220,236,255); px[bx+1,y]=OUT; px[bx-1,y]=OUT if y>hy+4 else px[bx-1,y]
    px[bx,hy+10]=OUT
im.save('hall_blossom_fixed.png')
c=im.crop((0,0,72*6,72)); bg=Image.new('RGBA',c.size,(120,170,90,255)); bg.alpha_composite(c); bg.resize((c.width*4,c.height*4),Image.NEAREST).save('ph_fixed.png')
print('ok')

from PIL import Image
im=Image.open('hall_blossom.png').convert('RGBA'); px=im.load()
def lum(c): return 0.3*c[0]+0.59*c[1]+0.11*c[2]
for f in range(6):
    ox=f*72
    cell=[(x,y) for x in range(ox,ox+72) for y in range(72) if px[x,y][3]>200]
    furs=sorted([px[x,y] for (x,y) in cell if lum(px[x,y])>150 and abs(px[x,y][0]-px[x,y][2])<60], key=lum)
    LIGHT=furs[int(len(furs)*0.85)]; MIDC=furs[int(len(furs)*0.45)]; DARKC=furs[int(len(furs)*0.1)]
    OUT=min((px[x,y] for (x,y) in cell), key=lum)
    # the old dagger: gone, fur in its place
    for (x,y) in cell:
        c=px[x,y]
        if (c[0]>200 and c[1]>150 and c[2]<120) or (c[2]>c[0]+15 and y>32 and x<ox+40): px[x,y]=MIDC
    def left(y):
        xs=[x for x in range(ox,ox+40) if px[x,y][3]>200]
        return min(xs) if xs else None
    # her side's line, from the shoulder to below the bent arm
    y0=24; y1=47
    while left(y0) is None: y0+=1
    e0=left(y0); e1=left(y1)
    for y in range(y0+1,y1):
        edge=int(round(e0+(e1-e0)*(y-y0)/(y1-y0)))+1
        for x in range(ox,edge-1): px[x,y]=(0,0,0,0)
        # fill any gap up to her side with fur, and draw her side's outline
        for x in range(edge,edge+6):
            if px[x,y][3]==0 or lum(px[x,y])<80: px[x,y]=MIDC
        px[edge-1,y]=OUT
    # the arm, hanging, three pixels wide, lit on its outer side
    def at(y): return int(round(e0+(e1-e0)*(y-y0)/(y1-y0)))
    top=y0+3; bot=y1-6
    for y in range(top,bot):
        a=at(y)-3
        px[a-1,y]=OUT; px[a,y]=LIGHT; px[a+1,y]=MIDC; px[a+2,y]=DARKC
    # the fist and the dagger in it, pointing down
    a=at(bot)-3
    for y in range(bot,bot+3):
        px[a-1,y]=OUT; px[a,y]=LIGHT; px[a+1,y]=MIDC; px[a+2,y]=MIDC; px[a+3,y]=OUT
    for x in range(a-1,a+4): px[x,bot+3]=OUT
    for x in range(a-1,a+4): px[x,bot+3]=(232,184,64,255)
    for y in range(bot+4,bot+11): px[a+1,y]=(214,220,236,255); px[a,y]=OUT; px[a+2,y]=OUT
    px[a+1,bot+11]=OUT
im.save('hall_blossom_fixed2.png')
c=im.crop((0,0,72*7,72)); bg=Image.new('RGBA',c.size,(120,170,90,255)); bg.alpha_composite(c); bg.resize((c.width*4,c.height*4),Image.NEAREST).save('ph_fixed2.png')
print('ok')

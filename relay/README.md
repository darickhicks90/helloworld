# 5IY jam relay

A tiny Cloudflare Worker that lets two copies of 5 Eye Yeti jam over the internet: it hands out
one-time codes, pairs the two phones, and passes the leader's broadcast (and the follower's
"hey wait" / "my turn" messages and role swaps) from one to the other.

- Codes are 8 characters, single use, and expire after 10 minutes if nobody joins.
- Only two phones per code; once the second joins, the code is spent.
- Everything travels over https / wss. The relay stores nothing and forgets a room when either
  phone leaves.

## Deploy (free plan is fine)

1. Make a free Cloudflare account at https://dash.cloudflare.com/sign-up.
2. Install Node.js (https://nodejs.org), then in this folder run:

   ```
   npx wrangler login
   npx wrangler deploy
   ```

3. Wrangler prints the relay's address, like `https://5iy-jam.<your-subdomain>.workers.dev`.
4. In the app: open the jam panel (Menu → Jam over the internet) and paste that address into
   "Relay address" once. Both phones need the same address.

A jam sends the leader's mix at about 96 kbps (under 1 MB a minute), in half-second packets, which
keeps hours of jamming a day inside the free plan.

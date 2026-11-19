package app.foxbox.jam

import android.app.Activity
import android.content.Intent
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import app.foxbox.ui.Btn
import app.foxbox.ui.Px
import app.foxbox.ui.Snes
import app.foxbox.ui.SnesDialog

/** The jam panel: relay address, start or join with a code, lead / follow, the follower's two messages, leave. */
object JamPanel {
    fun show(a: Activity) {
        Jam.init(a)
        Jam.onNotice = { Toast.makeText(a, it, Toast.LENGTH_LONG).show() }
        val box = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        val d = SnesDialog(a, "Jam over the internet").view(box)
        d.key("Close", Snes.PURPLE, then = null)
        Jam.onChange = { build(a, box) }
        d.dialog.setOnDismissListener { Jam.onChange = null }
        build(a, box)
        d.show()
    }

    private fun text(a: Activity, s: String, size: Float = 15f, big: Boolean = false) = TextView(a).apply {
        text = s; typeface = if (big) Px.head else Px.body; textSize = size; setTextColor(Snes.INK)
        gravity = if (big) Gravity.CENTER else Gravity.START
        setPadding(Px.dp(4), Px.dp(4), Px.dp(4), Px.dp(6))
    }

    private fun row(a: Activity, vararg btns: Btn) = LinearLayout(a).apply {
        orientation = LinearLayout.HORIZONTAL
        btns.forEach { addView(it, LinearLayout.LayoutParams(0, Px.dp(46), 1f).apply { rightMargin = Px.dp(6) }) }
    }

    private fun btn(a: Activity, s: String, color: Int, lit: Boolean = false, click: () -> Unit) = Btn(a, s, color).apply { on = lit; onClick = click }

    private fun build(a: Activity, box: LinearLayout) {
        box.removeAllViews()
        val wrap = ViewGroup.LayoutParams.MATCH_PARENT
        fun add(v: android.view.View) = box.addView(v, LinearLayout.LayoutParams(wrap, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = Px.dp(6) })
        when (Jam.state) {
            Jam.State.IDLE -> {
                add(text(a, "One phone leads and its mix goes out like a private radio broadcast (a few seconds behind). " +
                    "The other listens, and can send \"Hey, wait!\" or \"My turn!\", which the leader's phone says aloud."))
                val relay = Jam.relay
                add(text(a, if (relay.isBlank()) "Relay: not set yet (see relay/README.md)" else "Relay: " + relay.removePrefix("https://"), 13f))
                add(row(a, btn(a, if (relay.isBlank()) "Set relay address" else "Change relay", Snes.YELLOW) {
                    SnesDialog.text(a, "Relay address (https://…workers.dev)", relay.ifBlank { "https://" }) { Jam.relay = it; build(a, box) }
                }))
                add(row(a,
                    btn(a, "Start a jam", Snes.GREEN, true) { Jam.start() },
                    btn(a, "Join with a code", Snes.PURPLE, true) { SnesDialog.text(a, "Your partner's jam code", "") { Jam.join(it) } }))
                if (Jam.status.isNotBlank()) add(text(a, Jam.status, 13f))
            }
            Jam.State.CREATING, Jam.State.CONNECTING -> add(text(a, Jam.status))
            Jam.State.WAITING -> {
                val c = Jam.code ?: ""
                add(text(a, c.take(4) + "-" + c.drop(4), 26f, big = true))
                add(text(a, Jam.status + " Waiting for your partner…", 13f))
                add(row(a,
                    btn(a, "Share code", Snes.GREEN, true) {
                        a.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"; putExtra(Intent.EXTRA_TEXT, "Jam with me in 5 Eye Yeti: open the jam panel and join with code ${c.take(4)}-${c.drop(4)} (works once, for 10 minutes).")
                        }, "Share the jam code"))
                    },
                    btn(a, "Cancel", Snes.RED) { Jam.leave() }))
            }
            Jam.State.CONNECTED -> {
                val lead = Jam.role == Jam.Role.LEAD
                add(text(a, if (lead) "You're leading: your partner hears your mix, a few seconds behind." else "You're following: listening to your partner. Play along; they won't hear you.", 15f))
                // lead / follow: the swap happens once both phones have switched
                val partnerAsks = Jam.partnerWant != (if (lead) Jam.Role.FOLLOW else Jam.Role.LEAD)
                val waiting = Jam.myWant != Jam.role
                add(row(a,
                    btn(a, "Lead", Snes.GREEN, Jam.myWant == Jam.Role.LEAD) { Jam.want(Jam.Role.LEAD) },
                    btn(a, "Follow", Snes.YELLOW, Jam.myWant == Jam.Role.FOLLOW) { Jam.want(Jam.Role.FOLLOW) }))
                when {
                    waiting -> add(text(a, "Waiting for your partner to switch too…", 13f))
                    partnerAsks -> add(text(a, if (Jam.partnerWant == Jam.Role.LEAD) "Your partner wants to lead: tap Follow to hand it over." else "Your partner wants you to lead: tap Lead to take over.", 13f))
                }
                if (!lead) add(row(a,
                    btn(a, "Hey, wait!", Snes.YELLOW, true) { Jam.sayToLeader(false) },
                    btn(a, "My turn!", Snes.GREEN, true) { Jam.sayToLeader(true) }))
                add(row(a, btn(a, "Leave the jam", Snes.RED) { Jam.leave() }))
            }
        }
    }
}

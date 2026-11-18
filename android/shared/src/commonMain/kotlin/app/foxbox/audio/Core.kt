package app.foxbox.audio

import app.foxbox.engines.Bumble
import app.foxbox.engines.Gusts
import app.foxbox.engines.Leaves
import app.foxbox.engines.Minds
import app.foxbox.engines.Pickup
import app.foxbox.engines.Reflections
import app.foxbox.fx.FlowerRack
import app.foxbox.seq.Transport

/**
 * The sound core shared by the Android and iOS apps: every engine, the
 * transport and the flower rack. Each platform's audio host mixes them and
 * drives its device (Audio on Android, IosAudio on iOS).
 */
object Core {
    val transport = Transport()
    val gusts = Gusts()
    val reflections = Reflections()
    val leaves = Leaves()
    val minds = Minds()
    val bumble = Bumble()
    val pickup = Pickup()
    /** Every engine the app has (one per block type). */
    val engines: List<Engine> = listOf(gusts, reflections, leaves, minds, bumble, pickup)
    fun engine(id: String): Engine = engines.first { it.id == id }
    val flowers = FlowerRack()

    init {
        transport.engines = listOf(gusts, minds)
        leaves.reflectionsSample = { reflections.sample }
    }
}

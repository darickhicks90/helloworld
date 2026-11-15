package app.foxbox.blocks

import app.foxbox.audio.Audio
import app.foxbox.audio.Engine

/** What a block plays like, which decides its page layout. */
enum class BlockKind { MELODIC, DRUMS, PADS }

/**
 * A kind of Fox Box Block. `productId` is set for blocks sold as add-ons
 * (see data.Store); built-in blocks have none.
 */
class BlockType(
    val id: String,
    val title: String,
    val kind: BlockKind,
    val blurb: String,
    val productId: String? = null,
)

/** A Fox Box Block: a type that HAS an engine. */
class Block(val type: BlockType, val engine: Engine) {
    val id get() = type.id
    val title get() = type.title
    val melodic get() = type.kind == BlockKind.MELODIC
}

/** The catalogue of blocks. A session holds up to [MAX] of them. */
object Blocks {
    const val MAX = 6
    val DEFAULT_SESSION = listOf("gusts", "minds")

    val types = listOf(
        BlockType("gusts", "Gusts", BlockKind.MELODIC, "Virtual analog synth"),
        BlockType("reflections", "Reflections", BlockKind.MELODIC, "Sampler with time stretch"),
        BlockType("leaves", "Leaves", BlockKind.MELODIC, "Granular synth"),
        BlockType("minds", "Minds", BlockKind.DRUMS, "Generative drums"),
        BlockType("bumble", "Bumble", BlockKind.MELODIC, "Mono bass synth with sequencer"),
        BlockType("pickup", "Pickup", BlockKind.PADS, "Vintage 16-pad sampler"),
    )

    val all: List<Block> by lazy { types.map { Block(it, Audio.engine(it.id)) } }
    fun get(id: String): Block? = all.firstOrNull { it.id == id }
    fun type(id: String): BlockType? = types.firstOrNull { it.id == id }
}

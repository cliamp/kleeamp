package stream.kleeamp.mobile.play

import stream.kleeamp.mobile.model.Station

fun interface PlayFromList {
    operator fun invoke(item: Station, source: List<Station>, continuation: QueueContinuation?)
}

fun interface PlayNext {
    operator fun invoke(item: Station)
}

fun interface AddToQueue {
    operator fun invoke(item: Station)
}

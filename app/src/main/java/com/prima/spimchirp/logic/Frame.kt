package com.prima.spimchirp.logic

enum class Polarity(val symbol: Char) {
  PLUS('+'), MINUS('−')
}

data class Connection(
  val atomId: String,
  val polarity: Polarity
)

data class Frame(
  val name: String,
  val connections: List<Connection>
)

object GameFrames {
  val PLAYING = Frame(
    name = "playing",
    connections = listOf(
      Connection("headDir", Polarity.PLUS),
      Connection("constrainChain", Polarity.PLUS),
      Connection("boundsCheck", Polarity.PLUS),
      Connection("selfCheck", Polarity.PLUS),
      Connection("fruitCheck", Polarity.PLUS),
      Connection("comboUpdate", Polarity.PLUS),
      Connection("scoreUpdate", Polarity.PLUS)
    )
  )

  val MENU = Frame("menu", emptyList())
  val DEAD = Frame("dead", emptyList())
}

object ChirpFrames {
  val MENU = Frame("menu", emptyList())
  val PREPARE = Frame("prepare", emptyList())
  val TRANSMIT = Frame("transmit", emptyList())
  val RECEIVE = Frame("receive", emptyList())
}

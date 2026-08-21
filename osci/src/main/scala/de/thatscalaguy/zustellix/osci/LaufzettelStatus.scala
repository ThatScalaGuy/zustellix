package de.thatscalaguy.zustellix.osci

/** Outcome recorded on a [[Laufzettel]].
 *
 *  A delivery that reached OSCI carries the top feedback code the
 *  intermediary reported — `0xxx` success or `3xxx` warning on the success
 *  path, `9xxx` when the request was rejected. A delivery that failed before
 *  OSCI produced any feedback (resolver failure, transport failure, a
 *  rejected content signature) carries the error kind instead.
 */
enum LaufzettelStatus {

  /** Top OSCI feedback code (`0xxx`, `3xxx` or `9xxx`), e.g. `"0800"`. */
  case Feedback(code: String)

  /** No feedback code exists; `kind` names the [[OsciError]] variant that
   *  failed the delivery (e.g. `"OsciTransport"`, `"AgsNotInDvdv"`).
   */
  case Failed(kind: String)

  /** True when the request was executed: a `0xxx` or `3xxx` feedback code.
   *  `9xxx` codes and [[Failed]] records are failure records.
   */
  def delivered: Boolean = this match {
    case Feedback(code) => code.startsWith("0") || code.startsWith("3")
    case Failed(_)      => false
  }

  /** The plain string form — the feedback code or the error kind — as it
   *  was stored before this wrapper existed (for logs, DB columns, …).
   */
  def render: String = this match {
    case Feedback(code) => code
    case Failed(kind)   => kind
  }
}

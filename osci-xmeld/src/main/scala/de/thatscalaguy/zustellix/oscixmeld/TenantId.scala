package de.thatscalaguy.zustellix.oscixmeld

opaque type TenantId = String

object TenantId {
  def apply(s: String): TenantId = s
  extension (t: TenantId) def value: String = t
}

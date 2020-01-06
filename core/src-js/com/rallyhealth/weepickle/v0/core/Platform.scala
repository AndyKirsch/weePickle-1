package com.rallyhealth.weepickle.v0.core

object Platform{
  @inline def charAt(s: CharSequence, i: Int) = {
    if (i >= s.length) throw new StringIndexOutOfBoundsException(i)
    s.charAt(i)
  }
  @inline def charAt(s: String, i: Int) = {
    if (i >= s.length) throw new StringIndexOutOfBoundsException(i)
    s.charAt(i)
  }
  @inline def byteAt(s: Array[Byte], i: Int) = {
    if (i >= s.length) throw new IndexOutOfBoundsException(s"Index out of bounds: $i > ${s.length}")
    s(i)
  }
}

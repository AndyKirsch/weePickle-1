package com.rallyhealth.weejson.v1

import com.rallyhealth.weepickle.v1.core.Visitor

trait Transformer[I] {

  def transform[T](j: I, f: Visitor[_, T]): T
}

package otoroshi.utils.cache

import com.github.blemale.scaffeine.{Cache, Scaffeine}

import java.util.concurrent.ConcurrentHashMap
import scala.collection.concurrent.TrieMap
import scala.concurrent.duration.FiniteDuration

package object types {
  type LegitTrieMap[A, B]           = TrieMap[A, B]
  type LegitConcurrentHashMap[A, B] = ConcurrentHashMap[A, B]
}

object Caches {
  def bounded[A, B](maxItems: Int): Cache[A, B] = {
    Scaffeine().maximumSize(maxItems).build[A, B]()
  }
  def expireAfterWrite[A, B](duration: FiniteDuration, maxItems: Int = Int.MaxValue): Cache[A, B] = {
    Scaffeine().expireAfterWrite(duration).maximumSize(maxItems).build[A, B]()
  }
}

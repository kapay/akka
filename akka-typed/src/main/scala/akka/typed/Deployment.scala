/**
 * Copyright (C) 2016-2017 Lightbend Inc. <http://www.lightbend.com/>
 */
package akka.typed

import java.util.concurrent.Executor

import akka.annotation.{ ApiMayChange, DoNotInherit, InternalApi }

import scala.annotation.tailrec
import scala.concurrent.ExecutionContext
import scala.reflect.ClassTag

/**
 * Data structure for describing an actor’s props details like which
 * executor to run it on. For each type of setting (e.g. [[DispatcherSelector]]
 * or [[MailboxCapacity]]) the FIRST occurrence is used when creating the
 * actor; this means that adding configuration using the "with" methods
 * overrides what was configured previously.
 *
 * Deliberately not sealed in order to emphasize future extensibility by the
 * framework—this is not intended to be extended by user code.
 *
 * The Props includes a `next` reference so that it can form an
 * internally linked list. Traversal of this list stops when encountering the
 * [[EmptyProps]] object.
 */
@DoNotInherit
@ApiMayChange
abstract class Props private[akka] () extends Product with Serializable {
  /**
   * Reference to the tail of this Props list.
   *
   * INTERNAL API
   */
  @InternalApi
  private[akka] def next: Props

  /**
   * Create a copy of this Props node with its `next` reference
   * replaced by the given object. <b>This does NOT append the given list
   * of configuration nodes to the current list!</b>
   *
   * INTERNAL API
   */
  @InternalApi
  private[akka] def withNext(next: Props): Props

  /**
   * Prepend a selection of the [[ActorSystem]] default executor to this Props.
   */
  def withDispatcherDefault: Props = DispatcherDefault(this)

  /**
   * Prepend a selection of the executor found at the given Config path to this Props.
   * The path is relative to the configuration root of the [[ActorSystem]] that looks up the
   * executor.
   */
  def withDispatcherFromConfig(path: String): Props = DispatcherFromConfig(path, this)

  /**
   * Prepend a selection of the given executor to this Props.
   */
  def withDispatcherFromExecutor(executor: Executor): Props = DispatcherFromExecutor(executor, this)

  /**
   * Prepend a selection of the given execution context to this Props.
   */
  def withDispatcherFromExecutionContext(ec: ExecutionContext): Props = DispatcherFromExecutionContext(ec, this)

  /**
   * Prepend the given mailbox capacity configuration to this Props.
   */
  def withMailboxCapacity(capacity: Int): Props = MailboxCapacity(capacity, this)

  /**
   * Find the first occurrence of a configuration node of the given type, falling
   * back to the provided default if none is found.
   */
  @InternalApi
  private[akka] def firstOrElse[T <: Props: ClassTag](default: T): T = {
    @tailrec def rec(d: Props): T = {
      d match {
        case EmptyProps ⇒ default
        case t: T       ⇒ t
        case _          ⇒ rec(d.next)
      }
    }
    rec(this)
  }

  /**
   * Retrieve all configuration nodes of a given type in the order that they
   * are present in this Props. The `next` reference for all returned
   * nodes will be the [[EmptyProps]].
   */
  @InternalApi
  private[akka] def allOf[T <: Props: ClassTag]: List[Props] = {
    @tailrec def select(d: Props, acc: List[Props]): List[Props] =
      d match {
        case EmptyProps ⇒ acc.reverse
        case t: T       ⇒ select(d.next, (d withNext EmptyProps) :: acc)
        case _          ⇒ select(d.next, acc)
      }
    select(this, Nil)
  }

  /**
   * Remove all configuration nodes of a given type and return the resulting
   * Props.
   */
  @InternalApi
  private[akka] def filterNot[T <: Props: ClassTag]: Props = {
    @tailrec def select(d: Props, acc: List[Props]): List[Props] =
      d match {
        case EmptyProps ⇒ acc
        case t: T       ⇒ select(d.next, acc)
        case _          ⇒ select(d.next, d :: acc)
      }
    @tailrec def link(l: List[Props], acc: Props): Props =
      l match {
        case d :: ds ⇒ link(ds, d withNext acc)
        case Nil     ⇒ acc
      }
    link(select(this, Nil), EmptyProps)
  }
}

/**
 * Configure the maximum mailbox capacity for the actor. If more messages are
 * enqueued because the actor does not process them quickly enough then further
 * messages will be dropped.
 *
 * The default mailbox capacity that is used when this option is not given is
 * taken from the `akka.typed.mailbox-capacity` configuration setting.
 */
final case class MailboxCapacity(capacity: Int, next: Props = EmptyProps) extends Props {
  @InternalApi
  private[akka] override def withNext(next: Props): Props = copy(next = next)
}

/**
 * The empty configuration node, used as a terminator for the internally linked
 * list of each Props.
 */
case object EmptyProps extends Props {
  @InternalApi
  private[akka] override def next = throw new NoSuchElementException("EmptyProps has no next")
  @InternalApi
  private[akka] override def withNext(next: Props): Props = next
}

/**
 * Subclasses of this type describe which thread pool shall be used to run
 * the actor to which this configuration is applied.
 *
 * The default configuration if none of these options are present is to run
 * the actor on the same executor as its parent.
 */
sealed trait DispatcherSelector extends Props

/**
 * Use the [[ActorSystem]] default executor to run the actor.
 */
@DoNotInherit
@InternalApi
sealed case class DispatcherDefault(next: Props) extends DispatcherSelector {
  @InternalApi
  private[akka] override def withNext(next: Props): Props = copy(next = next)
}
object DispatcherDefault {
  // this is hidden in order to avoid having people match on this object
  private val empty = DispatcherDefault(EmptyProps)
  /**
   * Retrieve an instance for this configuration node with empty `next` reference.
   */
  def apply(): DispatcherDefault = empty
}

/**
 * Look up an executor definition in the [[ActorSystem]] configuration.
 * ExecutorServices created in this fashion will be shut down when the
 * ActorSystem terminates.
 */
final case class DispatcherFromConfig(path: String, next: Props = EmptyProps) extends DispatcherSelector {
  @InternalApi
  private[akka] override def withNext(next: Props): Props = copy(next = next)
}

/**
 * Directly use the given Executor whenever the actor needs to be run.
 * No attempt will be made to shut down this thread pool, even if it is an
 * instance of ExecutorService.
 */
final case class DispatcherFromExecutor(executor: Executor, next: Props = EmptyProps) extends DispatcherSelector {
  @InternalApi
  private[akka] override def withNext(next: Props): Props = copy(next = next)
}

/**
 * Directly use the given ExecutionContext whenever the actor needs to be run.
 * No attempt will be made to shut down this thread pool, even if it is an
 * instance of ExecutorService.
 */
final case class DispatcherFromExecutionContext(ec: ExecutionContext, next: Props = EmptyProps) extends DispatcherSelector {
  @InternalApi
  private[akka] override def withNext(next: Props): Props = copy(next = next)
}

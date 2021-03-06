/**
 * Copyright (C) 2010 Orbeon, Inc.
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the
 * GNU Lesser General Public License as published by the Free Software Foundation; either version
 * 2.1 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU Lesser General Public License for more details.
 *
 * The full text of the license is available at http://www.gnu.org/copyleft/lesser.html
 */
package org.orbeon.oxf.xforms.event

import org.orbeon.dom.{Element, QName}
import org.orbeon.oxf.util.{Logging, Modifier}
import org.orbeon.oxf.xforms.XFormsConstants._
import org.orbeon.oxf.xforms._
import org.orbeon.oxf.xforms.action.{XFormsAPI, XFormsActionInterpreter, XFormsActions}
import org.orbeon.oxf.xforms.analysis.ElementAnalysis._
import org.orbeon.oxf.xforms.analysis.controls.RepeatIterationControl
import org.orbeon.oxf.xforms.analysis.{ElementAnalysis, SimpleElementAnalysis, StaticStateContext}
import org.orbeon.oxf.xforms.control.XFormsComponentControl
import org.orbeon.oxf.xforms.event.events.XXFormsActionErrorEvent
import org.orbeon.oxf.xforms.xbl.Scope
import org.orbeon.oxf.xml.dom4j.Dom4jUtils
import org.orbeon.xforms.{EventNames, XFormsId}

import scala.util.control.NonFatal

/**
 * XForms (or just plain XML Events) event handler implementation.
 *
 * All event-related information gathered is immutable (the only temporarily mutable information is the base class's
 * XPath analysis, which is unused here).
 */
class EventHandlerImpl(
    staticStateContext : StaticStateContext,
    element            : Element,
    parent             : Option[ElementAnalysis],
    preceding          : Option[ElementAnalysis],
    scope              : Scope
) extends SimpleElementAnalysis(
    staticStateContext,
    element,
    parent,
    preceding,
    scope
) with EventHandler
  with Logging {

  self ⇒

  import EventHandlerImpl._

  // NOTE: We check attributes in the ev:* or no namespace. We don't need to handle attributes in the xbl:* namespace.
  private def att(name: QName)               : String         = element.attributeValue(name)
  private def attOption(name: QName)         : Option[String] = element.attributeValueOpt(name)
  // TODO: no null
  private def att(name1: QName, name2: QName)          =  attOption(name1) orElse attOption(name2) orNull

  // These are only relevant when listening to keyboard events
  val keyText      : Option[String] = attOption(XXFORMS_EVENTS_TEXT_ATTRIBUTE_QNAME)
  val keyModifiers : Set[Modifier]  = parseKeyModifiers(attOption(XXFORMS_EVENTS_MODIFIERS_ATTRIBUTE_QNAME))

  val eventNames: Set[String] = {

    val names =
      attSet(element, XML_EVENTS_EV_EVENT_ATTRIBUTE_QNAME) ++
        attSet(element, XML_EVENTS_EVENT_ATTRIBUTE_QNAME)

    // For backward compatibility, still support `keypress` even with modifiers, but translate that to `keydown`,
    // as modifiers require `keydown` in browsers.
    if (keyModifiers.nonEmpty)
      names map {
        case EventNames.KeyPress ⇒ EventNames.KeyDown
        case other               ⇒ other
      }
    else
      names
  }

  // NOTE: If #all is present, ignore all other specific events
  val (actualEventNames, isAllEvents) =
    if (eventNames(XXFORMS_ALL_EVENTS))
      (Set(XXFORMS_ALL_EVENTS), true)
    else
      (eventNames, false)

   // Q: is this going to be eliminated as a field?
  private val phaseAtt = att(XML_EVENTS_EV_PHASE_ATTRIBUTE_QNAME, XML_EVENTS_PHASE_ATTRIBUTE_QNAME)

  val isCapturePhaseOnly     = phaseAtt == "capture"
  val isTargetPhase          = (phaseAtt eq null) || Set("target", "default")(phaseAtt)
  val isBubblingPhase        = (phaseAtt eq null) || Set("bubbling", "default")(phaseAtt)
  // "true" means "continue", "false" means "stop"
  val isPropagate            = att(XML_EVENTS_EV_PROPAGATE_ATTRIBUTE_QNAME, XML_EVENTS_PROPAGATE_ATTRIBUTE_QNAME) != "stop"
  // "true" means "perform", "false" means "cancel"
  val isPerformDefaultAction = att(XML_EVENTS_EV_DEFAULT_ACTION_ATTRIBUTE_QNAME, XML_EVENTS_DEFAULT_ACTION_ATTRIBUTE_QNAME) != "cancel"

  val isPhantom       = att(XXFORMS_EVENTS_PHANTOM_ATTRIBUTE_QNAME) == "true"
  val isIfNonRelevant = attOption(XXFORMS_EVENTS_IF_NON_RELEVANT_ATTRIBUTE_QNAME) contains "true"

  assert(! (isPhantom && isWithinRepeat), "phantom observers are not supported within repeats at this time")

  val isXBLHandler = element.getQName == XBL_HANDLER_QNAME

  // Observers and targets

  // Temporarily mutable until after analyzeEventHandler() has run
  private var _observersPrefixedIds: Set[String] = _
  private var _targetPrefixedIds: Set[String] = _

  // Question: should we just point to the ElementAnalysis instead of using ids?
  def observersPrefixedIds = _observersPrefixedIds
  def targetPrefixedIds = _targetPrefixedIds

  // Analyze the handler
  def analyzeEventHandler(): Unit = {

    // This must run only once
    assert(_observersPrefixedIds eq null)
    assert(_targetPrefixedIds eq null)

    // Logging
    implicit val logger = staticStateContext.partAnalysis.getIndentedLogger

    def unknownTargetId(id: String) = {
      warn("unknown id", Seq("id" → id))
      Set.empty[String]
    }

    def ignoringHandler(attName: String) = {
      warn(attName + " attribute present but does not refer to at least one valid id, ignoring event handler",
         Seq("element" → Dom4jUtils.elementToDebugString(element)))
      Set.empty[String]
    }

    // Resolver for tokens
    type TokenResolver = PartialFunction[String, Set[String]]

    val staticIdResolver: TokenResolver = {
      case id ⇒
        val prefixedId = scope.prefixedIdForStaticId(id)
        if (prefixedId ne null)
          Set(prefixedId)
        else
          unknownTargetId(id)
    }

    // 1. Resolve observer(s)

    // Resolve a token starting with a hash (#)
    val observerHashResolver: TokenResolver = {
      case ObserverIsPrecedingSibling ⇒
        preceding match {
          case Some(p) ⇒ Set(p.prefixedId)
          case None    ⇒ unknownTargetId(ObserverIsPrecedingSibling)
        }
    }

    // Support `ev:observer` and plain `observer`
    // NOTE: Supporting space-separated observer/target ids is an extension, which may make it into XML Events 2
    val observersTokens               = attSet(element, XML_EVENTS_EV_OBSERVER_ATTRIBUTE_QNAME) ++ attSet(element, XML_EVENTS_OBSERVER_ATTRIBUTE_QNAME)
    val observersPrefixedIdsAndHashes = observersTokens flatMap (observerHashResolver orElse staticIdResolver)

    val ignoreDueToObservers = observersTokens.nonEmpty && observersPrefixedIdsAndHashes.isEmpty

    val observersPrefixedIds =
      if (ignoreDueToObservers)
        ignoringHandler("observer")
      else {
        if (observersPrefixedIdsAndHashes.nonEmpty)
          observersPrefixedIdsAndHashes
        else
          parent collect {
            case iteration: RepeatIterationControl ⇒
              // Case where the handler doesn't have an explicit observer and is within a repeat
              // iteration. As of 2012-05-18, the handler observes the enclosing repeat container.
              Set(iteration.parent.get.prefixedId)
            case parent: ElementAnalysis ⇒
              // Case where the handler doesn't have an explicit observer. It observes its parent.
              Set(parent.prefixedId)
          } getOrElse Set.empty[String]
      }

    // 2. Resolve target(s)

    // Resolve a token starting with a hash (#)
    val targetHashResolver: TokenResolver = {
      case TargetIsObserver ⇒ observersPrefixedIds
    }

    // Handle backward compatibility for <dispatch ev:event="…" ev:target="…" name="…" target="…">. In this case,
    // if the user didn't specify the `targetid` attribute, the meaning of the `target` attribute in no namespace is
    // the target of the dispatch action, not the incoming XML Events target. In this case to specify the incoming
    // XML Events target, the attribute must be qualified as `ev:target`.
    val isDispatchActionNoTargetId = XFormsActions.isDispatchAction(element.getQName) && (element.attribute(TARGET_QNAME) ne null) && (element.attribute(TARGETID_QNAME) eq null)

    val targetTokens                = attSet(element, XML_EVENTS_EV_TARGET_ATTRIBUTE_QNAME) ++ (if (isDispatchActionNoTargetId) Set() else attSet(element, XML_EVENTS_TARGET_ATTRIBUTE_QNAME))
    val targetsPrefixedIdsAndHashes = targetTokens flatMap (targetHashResolver orElse staticIdResolver)

    val ignoreDueToTarget = targetTokens.nonEmpty && targetsPrefixedIdsAndHashes.isEmpty
    if (ignoreDueToTarget)
      ignoringHandler("target")

    if (ignoreDueToObservers || ignoreDueToTarget) {
      _observersPrefixedIds = Set.empty[String]
      _targetPrefixedIds    = Set.empty[String]
    } else {
      _observersPrefixedIds = observersPrefixedIds
      _targetPrefixedIds    = targetsPrefixedIdsAndHashes
    }
  }

  /**
   * Process the event on the given observer.
   */
  def handleEvent(eventObserver: XFormsEventTarget, event: XFormsEvent): Unit = {

    assert(_observersPrefixedIds ne null)
    assert(_targetPrefixedIds ne null)

    val containingDocument = event.containingDocument

    // Find dynamic context within which the event handler runs
    val (container, handlerEffectiveId, xpathContext) =
      eventObserver match {

        // Observer is the XBL component itself but from the "inside"
        case componentControl: XFormsComponentControl if isXBLHandler ⇒

          if (componentControl.canRunEventHandlers(event)) {

            val xblContainer = componentControl.nestedContainer
            xblContainer.getContextStack.resetBindingContext()
            val stack = new XFormsContextStack(xblContainer, xblContainer.getContextStack.getCurrentBindingContext)

            val handlerEffectiveId =
              xblContainer.getFullPrefix + staticId + XFormsId.getEffectiveIdSuffixWithSeparator(componentControl.getEffectiveId)

            (xblContainer, handlerEffectiveId, stack)
          } else {
            debug("ignoring event dispatched to non-relevant component control", List(
              "name"       → event.name,
              "control id" → componentControl.effectiveId)
            )
            return
          }

        // Regular observer
        case _ ⇒

          // Resolve the concrete handler
          EventHandlerImpl.resolveHandler(containingDocument, this, eventObserver, event.targetObject) match {
            case Some(concreteHandler) ⇒

              val handlerContainer   = concreteHandler.container
              val handlerEffectiveId = concreteHandler.getEffectiveId
              val stack              = new XFormsContextStack(handlerContainer, concreteHandler.bindingContext)

              (handlerContainer, handlerEffectiveId, stack)
            case None ⇒
              return
          }
      }

    val handlerIsRelevant =
      containingDocument.findControlByEffectiveId(handlerEffectiveId) map (_.isRelevant) getOrElse {
        // Actions in models do not have a dynamic representation and so are not indexed at this time. In such
        // cases, we look at the relevance of the container.
        container.isRelevant
      }

    if (handlerIsRelevant || isIfNonRelevant) {
      try {
        val actionInterpreter = new XFormsActionInterpreter(container, xpathContext, element, handlerEffectiveId, event, eventObserver)
        XFormsAPI.withScalaAction(actionInterpreter) {
          actionInterpreter.runAction(self)
        }
      } catch {
        case NonFatal(t) ⇒
          // Something bad happened while running the action: dispatch error event to the root of the current scope
          // NOTE: We used to dispatch the event to XFormsContainingDocument, but that is no longer a event
          // target. We thought about dispatching to the root control of the current scope, BUT in case the action
          // is running within a model before controls are created, that won't be available. SO the answer is to
          // dispatch to what we know exists, and that is the current observer or the target. The observer is
          // "closer" from the action, so we dispatch to that.
          Dispatch.dispatchEvent(new XXFormsActionErrorEvent(eventObserver, t))
      }
    } else {
      debug("skipping non-relevant handler", List(
        "event"        → event.name,
        "observer"     → event.targetObject.getEffectiveId,
        "handler name" → localName,
        "handler id"   → handlerEffectiveId
      ))
    }
  }

  final def isMatchByName(eventName: String) =
    isAllEvents || eventNames(eventName)

  // Match if no target id is specified, or if any specified target matches
  private def isMatchTarget(targetPrefixedId: String) =
    targetPrefixedIds.isEmpty || targetPrefixedIds(targetPrefixedId)

  // Match both name and target
  final def isMatchByNameAndTarget(eventName: String, targetPrefixedId: String) =
    isMatchByName(eventName) && isMatchTarget(targetPrefixedId)
}

object EventHandlerImpl extends Logging {

  // Special observer id indicating that the observer is the preceding sibling control
  val ObserverIsPrecedingSibling = "#preceding-sibling"

  // Special target id indicating that the target is the observer
  val TargetIsObserver = "#observer"

  // Whether the element is an event handler (a known action element with @*:event)
  def isEventHandler(element: Element) =
    XFormsActions.isAction(element.getQName) && (element.attribute(XML_EVENTS_EV_EVENT_ATTRIBUTE_QNAME.localName) ne null)

  // Given a static handler, and concrete observer and target, try to find the concrete handler
  def resolveHandler(
    containingDocument : XFormsContainingDocument,
    handler            : EventHandlerImpl,
    eventObserver      : XFormsEventTarget,
    targetObject       : XFormsEventTarget
  ): Option[XFormsEventHandler] = {

    val resolvedObject =
      if (targetObject.scope == handler.scope) {
        // The scopes match so we can resolve the id relative to the target
        targetObject.container.resolveObjectByIdInScope(targetObject.getEffectiveId, handler.staticId)
      } else if (handler.isPhantom) {
        // Special case of a phantom handler
        // NOTE: For now, we only support phantom handlers outside of repeats so we can resolve by prefixed id
        containingDocument.findObjectByEffectiveId(handler.prefixedId)
      } else {
        // See https://github.com/orbeon/orbeon-forms/issues/243
        warn(
          "skipping event in different scope (see issue #243)",
          List(
            "target id"             → targetObject.getEffectiveId,
            "handler id"            → handler.prefixedId,
            "observer id"           → eventObserver.getEffectiveId,
            "target scope"          → targetObject.scope.scopeId,
            "handler scope"         → handler.scope.scopeId,
            "observer scope"        → eventObserver.scope.scopeId
          ))(
          containingDocument.getIndentedLogger(XFormsEvents.LOGGING_CATEGORY)
        )
        None
      }

    resolvedObject map (_.asInstanceOf[XFormsEventHandler])
  }

  def parseKeyModifiers(value: Option[String]): Set[Modifier] =
    value match {
      case Some(attValue) ⇒ Modifier.parseStringToSet(attValue)
      case None           ⇒ Set.empty[Modifier]
    }
}
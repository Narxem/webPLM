package spies

import play.api.libs.json._
import play.api.Logger

import plm.universe.World
import plm.universe.IWorldView

import plm.universe.Operation

import plm.universe.GridWorldCell
import plm.universe.GridWorldCellOperation

import plm.universe.bugglequest.BuggleOperation
import plm.universe.bugglequest.MoveBuggleOperation
import plm.universe.bugglequest.ChangeBuggleDirection
import plm.universe.bugglequest.ChangeBuggleCarryBaggle

import plm.universe.bugglequest.BuggleWorldCellOperation
import plm.universe.bugglequest.ChangeCellColor
import plm.universe.bugglequest.ChangeCellHasBaggle

import actors.PLMActor

class ExecutionSpy(plmActor: PLMActor, messageID: String) extends IWorldView {
  val logger: Logger = Logger(this.getClass)
  
  var world: World = _
    
  override def clone(): ExecutionSpy = {
    return new ExecutionSpy(plmActor, messageID)
  }
  
  def setWorld(w: World) {
    world = w
    world.addWorldUpdatesListener(this)
    plmActor.registerSpy(this)
  }
  
  def unregister() {
    world.removeWorldUpdatesListener(this)
  }
  
  implicit val gridWorldCellWrites = new Writes[GridWorldCell] {
    def writes(gridWorldCell: GridWorldCell): JsValue = {
      Json.obj(
        "x" -> gridWorldCell.getX,
        "y" -> gridWorldCell.getY
      )
    }
  }
  
  def changeCellHasBaggleWrite(changeCellHasBaggle: ChangeCellHasBaggle): JsValue = {
    Json.obj(
      "oldHasBaggle" -> changeCellHasBaggle.getOldHasBaggle,
      "newHasBaggle" -> changeCellHasBaggle.getNewHasBaggle
    )
  }
  
  def changeCellColorWrite(changeCellColor: ChangeCellColor): JsValue = {
    var oldColor = changeCellColor.getOldColor
    var newColor = changeCellColor.getNewColor
    Json.obj(
      "oldColor" -> List[Int](oldColor.getRed, oldColor.getGreen, oldColor.getBlue, oldColor.getAlpha),
      "newColor" -> List[Int](newColor.getRed, newColor.getGreen, newColor.getBlue, newColor.getAlpha)
    )
  }
  
  def buggleWorldCellOperationWrite(buggleWorldCellOperation: BuggleWorldCellOperation): JsValue = {
    buggleWorldCellOperation match {
      case changeCellColor: ChangeCellColor =>
        changeCellColorWrite(changeCellColor)
      case changeCellHasBaggle: ChangeCellHasBaggle =>
        changeCellHasBaggleWrite(changeCellHasBaggle)
      case _ =>
        Json.obj(
          "operation" -> "arf"    
        )
    }
  }
  
  def gridWorldCellOperationWrite(gridWorldCellOperation: GridWorldCellOperation): JsValue = {
    var json: JsValue = null
    gridWorldCellOperation match {
      case buggleWorldCellOperation: BuggleWorldCellOperation =>
        json = buggleWorldCellOperationWrite(buggleWorldCellOperation)
    }
    json = json.as[JsObject] ++ Json.obj(
        "cell" -> gridWorldCellOperation.getCell
    )
    return json
  }
  
  // Can't define a writer for each subclass 
  // Since it would be ambiguous which one to use
  // Have to define functions instead
  implicit object operationWrite extends Writes[Operation] {
    def writes(operation: Operation): JsValue = {
      var json: JsValue = null
      operation match {
        case buggleOperation: BuggleOperation =>
          json = buggleOperationWrite(buggleOperation)
        case gridWorldCellOperation: GridWorldCellOperation =>
          json = gridWorldCellOperationWrite(gridWorldCellOperation)
        case _ =>
          Json.obj(
            "operation" -> "arf"    
          )
      }
      json = json.as[JsObject] ++ Json.obj(
        "type" -> operation.getName
      )
      return json
    }
  }
  
  def buggleOperationWrite(buggleOperation: BuggleOperation): JsValue = {
    var json: JsValue = null
    buggleOperation match {
      case moveBuggleOperation: MoveBuggleOperation =>
        json = moveBuggleOperationWrite(moveBuggleOperation)
      case changeBuggleDirection: ChangeBuggleDirection =>
        json = changeBuggleDirectionWrite(changeBuggleDirection)
      case changeBuggleCarryBaggle: ChangeBuggleCarryBaggle =>
        json = changeBuggleCarryBaggleWrite(changeBuggleCarryBaggle)
    }
    json = json.as[JsObject] ++ Json.obj(
      "buggleID" -> buggleOperation.getBuggle.getName
    )
    return json
  }
  
  def moveBuggleOperationWrite(moveBuggleOperation: MoveBuggleOperation): JsValue = {
    Json.obj(
      "oldX" -> moveBuggleOperation.getOldX(),
      "oldY" -> moveBuggleOperation.getOldY(),
      "newX" -> moveBuggleOperation.getNewX(),
      "newY" -> moveBuggleOperation.getNewY()
    )
  }

  def changeBuggleCarryBaggleWrite(changeBuggleCarryBaggle: ChangeBuggleCarryBaggle): JsValue = {
    Json.obj(
      "oldCarryBaggle" -> changeBuggleCarryBaggle.getOldCarryBaggle,
      "newCarryBaggle" -> changeBuggleCarryBaggle.getNewCarryBaggle
    )
  }
  
  def changeBuggleDirectionWrite(changeBuggleDirection: ChangeBuggleDirection): JsValue = {
    Json.obj(
      "oldDirection" -> changeBuggleDirection.getOldDirection.intValue(),
      "newDirection" -> changeBuggleDirection.getNewDirection.intValue()
    )
  }
  
  /**
   * Called every time something changes: entity move, new entity, entity gets destroyed, etc.
   */
  def worldHasMoved() {
    if(!world.operations.isEmpty()) {
      logger.debug("The world moved!")
      
      var mapArgs: JsValue = Json.obj(
        "worldID" -> world.getName,
        "operations" -> world.operations.toArray(Array[Operation]()).toList
      )
      //logger.debug(Json.stringify(mapArgs))
      world.operations.clear()
      plmActor.sendMessage(messageID, mapArgs)
    
    }
  }
  
  /**
   * Called when entities are created or destroyed, not when they move
   */
  def worldHasChanged() {
    // Do not care?
  }
}
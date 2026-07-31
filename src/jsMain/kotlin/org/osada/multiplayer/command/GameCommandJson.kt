package org.osada.multiplayer.command

object GameCommandJson {
    @Suppress("CyclomaticComplexMethod", "LongMethod")
    fun decode(source: String): GameCommand {
        val value = JSON.parse<dynamic>(source)
        val actor = requiredInt(value.actorPlayerId, "actorPlayerId")
        return when (requiredString(value.kind, "kind")) {
            MoveUnit::class.simpleName ->
                MoveUnit(
                    unitId = requiredInt(value.unitId, "unitId"),
                    from = coordinate(value.from),
                    to = coordinate(value.to),
                    path = coordinates(value.path),
                    actorPlayerId = actor,
                )

            AttackUnit::class.simpleName ->
                AttackUnit(
                    requiredInt(value.attackerUnitId, "attackerUnitId"),
                    requiredInt(value.defenderUnitId, "defenderUnitId"),
                    actor,
                )

            ResupplyUnit::class.simpleName -> ResupplyUnit(requiredInt(value.unitId, "unitId"), actor)
            ReinforceUnit::class.simpleName ->
                ReinforceUnit(
                    requiredInt(value.unitId, "unitId"),
                    (value.strengthPoints as? Number)?.toInt(),
                    actor,
                )

            MountUnit::class.simpleName ->
                MountUnit(
                    requiredInt(value.unitId, "unitId"),
                    (value.transportEquipmentId as? Number)?.toInt(),
                    actor,
                )

            UnmountUnit::class.simpleName -> UnmountUnit(requiredInt(value.unitId, "unitId"), actor)
            DeployUnit::class.simpleName ->
                DeployUnit(requiredInt(value.unitId, "unitId"), coordinate(value.destination), actor)

            UndeployUnit::class.simpleName -> UndeployUnit(requiredInt(value.unitId, "unitId"), actor)
            PurchaseUnit::class.simpleName ->
                PurchaseUnit(
                    requiredInt(value.equipmentId, "equipmentId"),
                    (value.transportEquipmentId as? Number)?.toInt(),
                    actor,
                )

            UpgradeUnit::class.simpleName ->
                UpgradeUnit(
                    requiredInt(value.unitId, "unitId"),
                    requiredInt(value.equipmentId, "equipmentId"),
                    (value.transportEquipmentId as? Number)?.toInt(),
                    actor,
                )

            DisbandUnit::class.simpleName -> DisbandUnit(requiredInt(value.unitId, "unitId"), actor)
            ReorderReserve::class.simpleName ->
                ReorderReserve(
                    requiredInt(value.unitId, "unitId"),
                    requiredInt(value.destinationIndex, "destinationIndex"),
                    actor,
                )

            SetUnitAssignment::class.simpleName ->
                SetUnitAssignment(
                    requiredInt(value.unitId, "unitId"),
                    value.assignedParticipantId as? String,
                    actor,
                )

            EndTurnReady::class.simpleName -> EndTurnReady(value.ready as? Boolean ?: false, actor)
            EndPlayerTurn::class.simpleName -> EndPlayerTurn(actor)
            ChooseCampaignDialogueOption::class.simpleName ->
                ChooseCampaignDialogueOption(
                    requiredString(value.dialogueId, "dialogueId"),
                    requiredString(value.optionId, "optionId"),
                    actor,
                )

            ContinueCampaign::class.simpleName ->
                ContinueCampaign(requiredString(value.outcome, "outcome"), actor)

            else -> error("Unknown game command kind")
        }
    }

    private fun coordinates(value: dynamic): List<HexCoordinate> {
        require(js("Array.isArray(value)") as Boolean)
        return (0 until (value.length as Number).toInt()).map { coordinate(value[it]) }
    }

    private fun coordinate(value: dynamic): HexCoordinate =
        HexCoordinate(requiredInt(value.x, "x"), requiredInt(value.y, "y"))

    private fun requiredInt(
        value: dynamic,
        field: String,
    ): Int = (value as? Number)?.toInt() ?: error("Missing $field")

    private fun requiredString(
        value: dynamic,
        field: String,
    ): String = value as? String ?: error("Missing $field")
}

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
                    cautious = value.cautious == true,
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
            LayMines::class.simpleName -> LayMines(requiredInt(value.unitId, "unitId"), actor)
            BarrageHex::class.simpleName ->
                BarrageHex(
                    unitId = requiredInt(value.unitId, "unitId"),
                    target = coordinate(value.target),
                    actorPlayerId = actor,
                )

            ClearMines::class.simpleName -> ClearMines(requiredInt(value.unitId, "unitId"), actor)
            BeginEngineering::class.simpleName ->
                BeginEngineering(
                    requiredInt(value.unitId, "unitId"),
                    (value.work as? String).orEmpty(),
                    actor,
                )

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

            EmbarkUnit::class.simpleName -> EmbarkUnit(requiredInt(value.unitId, "unitId"), actor)
            DisembarkUnit::class.simpleName -> DisembarkUnit(requiredInt(value.unitId, "unitId"), actor)
            EntrainUnit::class.simpleName -> EntrainUnit(requiredInt(value.unitId, "unitId"), actor)
            DetrainUnit::class.simpleName -> DetrainUnit(requiredInt(value.unitId, "unitId"), actor)

            GreenReinforceUnit::class.simpleName -> GreenReinforceUnit(requiredInt(value.unitId, "unitId"), actor)
            DismissLeader::class.simpleName -> DismissLeader(requiredInt(value.unitId, "unitId"), actor)
            PurchaseAttachment::class.simpleName ->
                PurchaseAttachment(requiredInt(value.unitId, "unitId"), intList(value.slots), actor)

            ProposeTransfer::class.simpleName ->
                ProposeTransfer(requiredInt(value.unitId, "unitId"), value.toCommanderId as? String, actor)

            RespondTransfer::class.simpleName ->
                RespondTransfer(requiredInt(value.unitId, "unitId"), value.accept as? Boolean ?: false, actor)

            SetLoadout::class.simpleName -> SetLoadout(stringList(value.abilityIds), actor)
            SetSeatSymbol::class.simpleName -> SetSeatSymbol(value.symbol as? String ?: "", actor)
            ResolveCampaignVote::class.simpleName ->
                ResolveCampaignVote(
                    requiredString(value.optionId, "optionId"),
                    actor,
                )
            BriefingReady::class.simpleName -> BriefingReady(actor)
            ProposePurchase::class.simpleName ->
                ProposePurchase(
                    requiredInt(value.equipmentId, "equipmentId"),
                    (value.transportEquipmentId as? Number)?.toInt(),
                    actor,
                )
            RespondPurchase::class.simpleName ->
                RespondPurchase(
                    requiredString(value.proposalId, "proposalId"),
                    value.accept as? Boolean ?: false,
                    actor,
                )

            ExcuseSeat::class.simpleName ->
                ExcuseSeat(
                    requiredString(value.commanderId, "commanderId"),
                    value.excused as? Boolean ?: false,
                    value.delegateToCommanderId as? String,
                    actor,
                )

            else -> error("Unknown game command kind")
        }
    }

    private fun coordinates(value: dynamic): List<HexCoordinate> {
        require(js("Array.isArray(value)") as Boolean)
        return (0 until (value.length as Number).toInt()).map { coordinate(value[it]) }
    }

    private fun stringList(value: dynamic): List<String> {
        require(js("Array.isArray(value)") as Boolean)
        return (0 until (value.length as Number).toInt()).map { requiredString(value[it], "abilityIds") }
    }

    private fun intList(value: dynamic): List<Int> {
        require(js("Array.isArray(value)") as Boolean)
        return (0 until (value.length as Number).toInt()).map { requiredInt(value[it], "slots") }
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

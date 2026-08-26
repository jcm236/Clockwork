package org.valkyrienskies.clockwork.content.forces

import com.fasterxml.jackson.annotation.JsonAutoDetect
import net.minecraft.core.Direction
import net.minecraft.util.Mth
import org.joml.Quaterniond
import org.joml.Vector3d
import org.joml.Vector3dc
import org.valkyrienskies.clockwork.ClockworkConfig
import org.valkyrienskies.clockwork.content.contraptions.propeller.data.PropCreateData
import org.valkyrienskies.clockwork.content.contraptions.propeller.data.PropData
import org.valkyrienskies.clockwork.content.contraptions.propeller.data.PropUpdateData
import org.valkyrienskies.core.api.VsBeta
import org.valkyrienskies.core.api.ships.LoadedServerShip
import org.valkyrienskies.core.api.ships.PhysShip
import org.valkyrienskies.core.api.util.GameTickOnly
import org.valkyrienskies.core.api.util.PhysTickOnly
import org.valkyrienskies.core.api.world.PhysLevel
import org.valkyrienskies.core.api.world.properties.DimensionId
import org.valkyrienskies.core.internal.ships.VsiPhysShip
import org.valkyrienskies.mod.common.util.toJOMLD
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.*

private data class ThrustSurface(
    val radius: Double,
    val pitchRadians: Double,
    val chordWidth: Double,
    val angularOffset: Double, // radians
    val isSail: Boolean = false,
    val axialOffset: Double = 0.0
)

private data class AeroEnvironment(
    val worldAxis: Vector3dc,
    val clockwiseAxis: Vector3dc,
    val referencePropAxis: Vector3dc,
    val airDensity: Double,
    val axialInflowVelocity: Double,
    val machFalloff: Double,
    val omegaSign: Double
)
@Suppress("UnstableApiUsage")
@VsBeta
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
class NewProppityBoppity(
    override val appliers: HashMap<Int, PropData> = HashMap(),
    override val applierUpdateData: ConcurrentLinkedQueue<Pair<Int, PropUpdateData>> = ConcurrentLinkedQueue(),
    override val createdAppliers: ConcurrentLinkedQueue<Pair<Int, PropCreateData>> = ConcurrentLinkedQueue(),
    override val removedAppliers: ConcurrentLinkedQueue<Int> = ConcurrentLinkedQueue(),
    override var nextApplierID: Int = 0
) : MultiInstanceForceApplier<PropUpdateData, PropData, PropCreateData> {

    var dimensionId: DimensionId = "minecraft:dimension:minecraft:overworld"
    var ticksSinceLastUpdate = 0


    @OptIn(PhysTickOnly::class)
    override fun physTick(physShip: PhysShip, physLevel: PhysLevel) {
        if (applierUpdateData.isNotEmpty()) ticksSinceLastUpdate = 0
        super.physTick(physShip, physLevel)

        for (physData in appliers.values) {
            if (!physData.active) continue

            val (force, torque) = computeNetForce(physShip, physData, physLevel)
            if (!force.isFinite || !torque.isFinite) continue

            if (physData.brass) {
                physShip.applyWorldForceToBodyPos(force)
            } else {
                physShip.applyWorldForceToModelPos(force, Vector3d(physData.position).add(0.5, 0.5, 0.5))
            }
            physShip.applyWorldTorque(torque)
        }

        ticksSinceLastUpdate++
    }

    @OptIn(PhysTickOnly::class)
    private fun computeNetForce(
        physShip: PhysShip,
        physProp: PropData,
        physLevel: PhysLevel
    ): Pair<Vector3dc, Vector3dc> {
        val surfaces = resolveSurfaces(physProp)
        if (surfaces.isEmpty()) {
            // empty prop :wompwomp:
            return Vector3d() to Vector3d()
        }

        val env = computeEnvironment(physShip, physProp, physLevel) ?: return Vector3d() to Vector3d()

        val estAngle = Math.toRadians(
            (physProp.bearingAngle + (physProp.bearingSpeed / 3.0 * ticksSinceLastUpdate.toDouble())) % 360.0
        )

        val netForce = Vector3d()
        val netTorque = Vector3d()

        for (surface in surfaces) {
            val (force, torque) = computeSurfaceForce(surface, env, physProp, estAngle, physShip)
            netForce.add(force)
            netTorque.add(torque)
        }

        clampVectorMagnitudeInPlace(netForce, ClockworkConfig.SERVER.propellerMaxForce)
        clampVectorMagnitudeInPlace(netTorque, ClockworkConfig.SERVER.propellerMaxTorque)

        return netForce to netTorque
    }

    private fun resolveSurfaces(physProp: PropData): List<ThrustSurface> {
        val blades = physProp.blades

        if (blades.isNotEmpty()) {
            val angleBetweenBlades = 2 * Math.PI / blades.size
            return blades.mapIndexed { i, blade ->
                ThrustSurface(
                    radius = blade.length,
                    pitchRadians = -Math.toRadians(blade.angle),
                    chordWidth = if (blade.wide) 0.375 else 0.25,
                    angularOffset = angleBetweenBlades * i,
                )
            }
        }

        val sails = physProp.sailPositions
        if (!sails.isNullOrEmpty()) {
            val referencePropAxis = physProp.bearingAxisRot ?: physProp.bearingAxis!!
            val clockwiseAxis: Vector3dc =
                if (physProp.bearingAxis == Direction.UP.normal.toJOMLD()) Direction.NORTH.normal.toJOMLD()
                else Direction.UP.normal.toJOMLD()

            return sails.mapNotNull { pos ->
                val posVec = Vector3d(pos)
                //Flattens the sail blocks into a single plane
                val axialComponent = Vector3d(referencePropAxis).mul(posVec.dot(referencePropAxis))
                val radial = Vector3d(posVec).sub(axialComponent)
                val radius = radial.length()
                if (radius < 1e-4) return@mapNotNull null // on-axis position, no lever arm

                val cosA = radial.dot(clockwiseAxis) / radius
                val cross = Vector3d(clockwiseAxis).cross(radial)
                val sinA = cross.dot(referencePropAxis) / radius
                val angularOffset = atan2(sinA, cosA)
                val signedAxialOffset = posVec.dot(referencePropAxis)

                ThrustSurface(
                    radius = radius,
                    pitchRadians = Math.toRadians(12.0), // fallback only; overridden when adaptivePitch is used
                    chordWidth = 1.0,
                    angularOffset = angularOffset,
                    isSail = true,
                    axialOffset = signedAxialOffset
                )
            }
        }

        return emptyList()
    }

    @OptIn(PhysTickOnly::class, GameTickOnly::class)
    private fun computeEnvironment(
        physShip: PhysShip,
        physProp: PropData,
        physLevel: PhysLevel
    ): AeroEnvironment? {
        val internal = physShip as VsiPhysShip
        val wind = internal.dragController?.getWindVector() ?: Vector3d()

        val baseAxis = physProp.bearingAxisRot ?: physProp.bearingAxis!!
        val baseClockwiseAxis: Vector3dc =
            if (physProp.bearingAxis == Direction.UP.normal.toJOMLD()) Direction.NORTH.normal.toJOMLD()
            else Direction.UP.normal.toJOMLD()

        // Cyclic tilt (copter swashplate control). The original computeForce()
        // applied this only to sail *position* for velocity purposes, while
        // still firing thrust along the untilted axis — that looks like an
        // oversight rather than intent (a tilted rotor disc that doesn't
        // redirect thrust can't actually steer). Here the tilt is applied
        // consistently to axis, clockwise reference, AND lever arm, so tilt
        // actually redirects thrust. This is a deliberate behavior change
        // from the original, not a straight port — flagging it as such
        // rather than silently "fixing" it.
        val tiltQuat = physProp.bearingTiltQuat ?: Quaterniond()
        val referencePropAxis = tiltQuat.transform(Vector3d(baseAxis))
        val clockwiseAxis: Vector3dc = tiltQuat.transform(Vector3d(baseClockwiseAxis))

        val worldAxis = physShip.transform.shipToWorld.transformDirection(baseAxis, Vector3d()).normalize(Vector3d())

        val totalVelocityAtProp = physShip.velocity
            .add(wind, Vector3d())
            .add(
                physShip.angularVelocity.cross(
                    Vector3d(physProp.position!!).sub(physShip.centerOfMass, Vector3d()).add(0.5, 0.5, 0.5),
                    Vector3d()
                ),
                Vector3d()
            )

        val axialInflowVelocity = totalVelocityAtProp.dot(worldAxis)
        if (axialInflowVelocity.isNaN() || axialInflowVelocity.isInfinite()) return null

        val machFalloff = 1 - 1.0 / (1 + exp((331 - abs(axialInflowVelocity)) / 30.0))
        val omegaSign = sign(physProp.bearingSpeed).let { if (it == 0.0) 1.0 else it }
        val airDensity = physLevel.aerodynamicUtils.getAirDensityForY(physShip.transform.positionInWorld.y(), dimensionId)

        return AeroEnvironment(
            worldAxis = worldAxis,
            clockwiseAxis = clockwiseAxis,
            referencePropAxis = referencePropAxis,
            airDensity = airDensity,
            axialInflowVelocity = axialInflowVelocity,
            machFalloff = machFalloff,
            omegaSign = omegaSign
        )
    }

    @OptIn(PhysTickOnly::class)
    private fun computeSurfaceForce(
        surface: ThrustSurface,
        env: AeroEnvironment,
        physProp: PropData,
        estAngle: Double,
        physShip: PhysShip
    ): Pair<Vector3dc, Vector3dc> {

        if (abs(surface.axialOffset) > 4.0) {
            return Vector3d() to Vector3d()
        }

        val bearingSpeed = physProp.bearingSpeed
        val rotationalVelocity = bearingSpeed.absoluteValue * surface.radius
        val absVt = abs(rotationalVelocity)
        if (absVt < 1e-4) return Vector3d() to Vector3d()

        val vtMin = 0.5
        val vtFade = 2.0
        val spinFactor = ((absVt - vtMin) / vtFade).coerceIn(0.0, 1.0)
        val sf = spinFactor * spinFactor * (3.0 - 2.0 * spinFactor) // smoothstep

        val phi = atan2(env.axialInflowVelocity, rotationalVelocity)

        // Legacy sail behavior: instead of a fixed authored pitch, self-trim
        // toward an "optimal" angle of attack over time. Ported as-is from the
        // old computeForce(), including the original quirk that all surfaces
        // on a given propeller share and stomp the same `currentBladePitch`
        // state (it was never per-sail) — noted here rather than silently fixed,
        // since fixing it changes tuning/feel and should be a deliberate call.
        val pitch = if (surface.isSail) {
            val optimalAngleOfAttack = -Math.toRadians(4.0)
            val optimalPitch = phi + optimalAngleOfAttack
            val minPitch = Math.toRadians(-5.0)
            val maxPitch = Math.toRadians(30.0)
            val lerped = Mth.lerp(0.05f, physProp.currentBladePitch.toFloat(), optimalPitch.toFloat()).toDouble()
                .coerceIn(minPitch, maxPitch)
            physProp.currentBladePitch = lerped
            lerped
        } else {
            surface.pitchRadians
        }

        val angleOfAttack = pitch - phi

        val liftCoefficient = 2.0 * Math.PI * angleOfAttack
        val dragCoefficient = 0.01 + 0.01 * liftCoefficient.pow(2.0)

        val induced = 0.5
        val vA = -env.axialInflowVelocity + induced
        val effectiveVelocity = sqrt(vA * vA + rotationalVelocity * rotationalVelocity)

        // dA is set to 1 for sails so the math ends up mathing to the old formula
        val dA = if (surface.isSail) 1.0 else surface.chordWidth * surface.radius

        val q = 0.5 * env.airDensity * effectiveVelocity.pow(2.0)
        val dLift = q * dA * liftCoefficient
        val dDrag = q * dA * dragCoefficient

        val dThrust = (dLift * cos(phi) - dDrag * sin(phi)) * sf * env.machFalloff

        val force = env.worldAxis
            .mul(dThrust * ClockworkConfig.SERVER.forceMulPerSailInPropeller, Vector3d())
            .mul(env.omegaSign, Vector3d())

        // Reaction torque, computed uniformly for every surface — previously
        // present (roughly) in the sail path only and always zero in the
        // blade path. This is the "decide the torque question once" item
        // from the migration checklist.
        val bladeAngle = estAngle + surface.angularOffset
        val leverShip = env.clockwiseAxis
            .mul(surface.radius, Vector3d())
            .rotateAxis(bladeAngle, env.referencePropAxis.x(), env.referencePropAxis.y(), env.referencePropAxis.z(), Vector3d())
        val leverWorld = physShip.transform.shipToWorld.transformDirection(leverShip, Vector3d())
        val torque = leverWorld.cross(force, Vector3d())


        // not sure if intentional or not :thonk:.
        // nevermind it seems to be needed
        val (finalForce, finalTorque) = if (surface.isSail) {
            val falloffSafe = abs(surface.axialOffset).coerceAtLeast(0.1)
            force.div(falloffSafe, Vector3d()) to torque.div(falloffSafe, Vector3d())
        } else {
            force to torque
        }

        return finalForce to finalTorque
//        return force to torque
    }

    private fun clampVectorMagnitudeInPlace(vec: Vector3d, maxMagnitude: Double) {
        if (maxMagnitude <= 0.0) return
        val maxSq = maxMagnitude * maxMagnitude
        if (vec.lengthSquared() > maxSq) {
            vec.normalize(maxMagnitude)
        }
    }

    private fun setDimension(dimID: DimensionId) {
        dimensionId = dimID
    }

    companion object {
        @OptIn(GameTickOnly::class)
        fun getOrCreate(ship: LoadedServerShip): NewProppityBoppity? {
            if (ship.getAttachment(NewProppityBoppity::class.java) == null) {
                val controller = NewProppityBoppity()
                controller.setDimension(ship.chunkClaimDimension)
                ship.setAttachment(controller)
            }
            return ship.getAttachment(NewProppityBoppity::class.java)
        }
        // idk wtf ts is for but ig ill keep it
        fun calculateBladePower(velocityTowardsPropellerDir: Double,
                                bladeRotationalSpeed: Double, bladeLength: Double, bladeAngle: Double, bladeWidth: Double): Double {
            // Magic balancing constants
            val b = 1.0
            val c = 4.0

            // Transform blade angle to usable range for powerCoefficient
            val a = ln(Mth.clamp(abs(bladeAngle), 0.0, 90.0) + 1)/2.3
            if (a == 0.0) return 0.0

            // TODO: Calculate speed of sound
            val airspeed = Mth.clamp(velocityTowardsPropellerDir, 0.0, 331.0)
            val advanceRatio = airspeed / (bladeRotationalSpeed * bladeLength)
            val powerCoefficient =  b * (a - 1 - advanceRatio/(a.pow(3.0) * c.pow(2.0)))
            val machPowerMultiplier = 1 - 1.0/(1+ exp((331 - airspeed) / 30.0))
            val power = powerCoefficient *
                    bladeRotationalSpeed.pow(3) *
                    (2 * bladeLength).pow(5) *
                    bladeWidth * 4 *
                    machPowerMultiplier

            return max(power, 0.0)
        }
    }

}
/*
 * MIT License
 *
 * Copyright (c) PhotonVision
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package frc.robot;

import static frc.robot.Constants.Vision.*;

import edu.wpi.first.math.geometry.Pose2d;
import edu.wpi.first.math.geometry.Rotation2d;
import edu.wpi.first.math.util.Units;
import edu.wpi.first.wpilibj.TimedRobot;
import edu.wpi.first.wpilibj.XboxController;
import edu.wpi.first.wpilibj.simulation.BatterySim;
import edu.wpi.first.wpilibj.simulation.RoboRioSim;
import edu.wpi.first.wpilibj.smartdashboard.SmartDashboard;
import frc.robot.subsystems.drivetrain.SwerveDrive;
import org.photonvision.PhotonCamera;
import org.photonvision.PhotonUtils;

public class Robot extends TimedRobot {
    private SwerveDrive drivetrain;
    private VisionSim visionSim;
    private PhotonCamera camera;

    private final double VISION_TURN_kP = 0.01;
    private final double VISION_DES_ANGLE_deg = 0.0;
    private final double VISION_STRAFE_kP = 0.5;
    private final double VISION_DES_RANGE_m = 1.25;

    private XboxController controller;

    @Override
    public void robotInit() {
        drivetrain = new SwerveDrive();
        camera = new PhotonCamera(kCameraName);

        visionSim = new VisionSim(camera);

        controller = new XboxController(0);
    }

    @Override
    public void robotPeriodic() {
        // Update drivetrain subsystem
        drivetrain.periodic();

        // Log values to the dashboard
        drivetrain.log();
    }

    @Override
    public void disabledPeriodic() {
        drivetrain.stop();
    }

    @Override
    public void teleopInit() {
        resetPose();
    }

    @Override
    public void teleopPeriodic() {
        // Calculate drivetrain commands from Joystick values
        double forward = -controller.getLeftY() * Constants.Swerve.kMaxLinearSpeed;
        double strafe = -controller.getLeftX() * Constants.Swerve.kMaxLinearSpeed;
        double turn = -controller.getRightX() * Constants.Swerve.kMaxAngularSpeed;

        // Read in relevant data from the Camera
        boolean targetVisible = false;
        double targetYaw = 0.0;
        double targetRange = 0.0;
        var results = camera.getAllUnreadResults();
        if (!results.isEmpty()) {
            // Camera processed a new frame since last
            // Get the last one in the list.
            var result = results.get(results.size() - 1);
            if (result.hasTargets()) {
                // At least one AprilTag was seen by the camera
                for (var target : result.getTargets()) {
                    if (target.getFiducialId() == 7) {
                        // Found Tag 7, record its information
                        targetYaw = target.getYaw();
                        targetRange =
                                PhotonUtils.calculateDistanceToTargetMeters(
                                        0.5, // Measured with a tape measure, or in CAD.
                                        1.435, // From 2024 game manual for ID 7
                                        Units.degreesToRadians(-30.0), // Measured with a protractor, or in CAD.
                                        Units.degreesToRadians(target.getPitch()));

                        targetVisible = true;
                    }
                }
            }
        }

        // Auto-align when requested
        if (controller.getAButton() && targetVisible) {
            // Driver wants auto-alignment to tag 7
            // And, tag 7 is in sight, so we can turn toward it.
            // Override the driver's turn and fwd/rev command with an automatic one
            // That turns toward the tag, and gets the range right.
            turn =
                    (VISION_DES_ANGLE_deg - targetYaw) * VISION_TURN_kP * Constants.Swerve.kMaxAngularSpeed;
            forward =
                    (VISION_DES_RANGE_m - targetRange) * VISION_STRAFE_kP * Constants.Swerve.kMaxLinearSpeed;
        }

        // Command drivetrain motors based on target speeds
        drivetrain.drive(forward, strafe, turn);

        // Put debug information to the dashboard
        SmartDashboard.putBoolean("Vision Target Visible", targetVisible);
        SmartDashboard.putNumber("Vision Target Range (m)", targetRange);
    }

    @Override
    public void simulationPeriodic() {
        // Update drivetrain simulation
        drivetrain.simulationPeriodic();

        // Update camera simulation
        visionSim.simulationPeriodic(drivetrain.getSimPose());

        var debugField = visionSim.getSimDebugField();
        debugField.getObject("EstimatedRobot").setPose(drivetrain.getPose());
        debugField.getObject("EstimatedRobotModules").setPoses(drivetrain.getModulePoses());

        // Calculate battery voltage sag due to current draw
        RoboRioSim.setVInVoltage(
                BatterySim.calculateDefaultBatteryLoadedVoltage(drivetrain.getCurrentDraw()));
    }

    public void resetPose() {
        // Example Only - startPose should be derived from some assumption
        // of where your robot was placed on the field.
        // The first pose in an autonomous path is often a good choice.
        var startPose = new Pose2d(1, 1, new Rotation2d());
        drivetrain.resetPose(startPose, true);
        visionSim.resetSimPose(startPose);
    }
}

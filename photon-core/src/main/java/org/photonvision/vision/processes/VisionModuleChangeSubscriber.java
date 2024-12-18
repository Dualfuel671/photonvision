/*
 * Copyright (C) Photon Vision.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package org.photonvision.vision.processes;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import org.apache.commons.lang3.tuple.Pair;
import org.opencv.core.Point;
import org.photonvision.common.dataflow.DataChangeSubscriber;
import org.photonvision.common.dataflow.events.DataChangeEvent;
import org.photonvision.common.dataflow.events.IncomingWebSocketEvent;
import org.photonvision.common.logging.LogGroup;
import org.photonvision.common.logging.Logger;
import org.photonvision.common.util.file.JacksonUtils;
import org.photonvision.common.util.numbers.DoubleCouple;
import org.photonvision.common.util.numbers.IntegerCouple;
import org.photonvision.vision.calibration.CameraCalibrationCoefficients;
import org.photonvision.vision.pipeline.AdvancedPipelineSettings;
import org.photonvision.vision.pipeline.PipelineType;
import org.photonvision.vision.pipeline.UICalibrationData;
import org.photonvision.vision.target.RobotOffsetPointOperation;

@SuppressWarnings("unchecked")
public class VisionModuleChangeSubscriber extends DataChangeSubscriber {
    private final VisionModule parentModule;
    private final Logger logger;
    private List<VisionModuleChange<?>> settingChanges = new ArrayList<>();
    private final ReentrantLock changeListLock = new ReentrantLock();

    public VisionModuleChangeSubscriber(VisionModule parentModule) {
        this.parentModule = parentModule;
        logger =
                new Logger(
                        VisionModuleChangeSubscriber.class,
                        parentModule.visionSource.getSettables().getConfiguration().nickname,
                        LogGroup.VisionModule);
    }

    @Override
    public void onDataChangeEvent(DataChangeEvent<?> event) {
        if (event instanceof IncomingWebSocketEvent) {
            var wsEvent = (IncomingWebSocketEvent<?>) event;

            // Camera index -1 means a "multicast event" (i.e. the event is received by all
            // cameras)
            if (wsEvent.cameraIndex != null
                    && (wsEvent.cameraIndex == parentModule.moduleIndex || wsEvent.cameraIndex == -1)) {
                logger.trace("Got PSC event - propName: " + wsEvent.propertyName);
                changeListLock.lock();
                try {
                    getSettingChanges()
                            .add(
                                    new VisionModuleChange(
                                            wsEvent.propertyName,
                                            wsEvent.data,
                                            parentModule.pipelineManager.getCurrentPipeline().getSettings(),
                                            wsEvent.originContext));
                } finally {
                    changeListLock.unlock();
                }
            }
        }
    }

    public List<VisionModuleChange<?>> getSettingChanges() {
        return settingChanges;
    }

    public void processSettingChanges() {
        // special case for non-PipelineSetting changes
        changeListLock.lock();
        try {
            for (var change : settingChanges) {
                var propName = change.getPropName();
                var newPropValue = change.getNewPropValue();
                var currentSettings = change.getCurrentSettings();
                var originContext = change.getOriginContext();
                switch (propName) {
                    case "pipelineName": // rename current pipeline
                        logger.info("Changing nick to " + newPropValue);
                        parentModule.pipelineManager.getCurrentPipelineSettings().pipelineNickname =
                                (String) newPropValue;
                        parentModule.saveAndBroadcastAll();
                        continue;
                    case "newPipelineInfo": // add new pipeline
                        var typeName = (Pair<String, PipelineType>) newPropValue;
                        var type = typeName.getRight();
                        var name = typeName.getLeft();

                        logger.info("Adding a " + type + " pipeline with name " + name);

                        var addedSettings = parentModule.pipelineManager.addPipeline(type);
                        addedSettings.pipelineNickname = name;
                        parentModule.saveAndBroadcastAll();
                        continue;
                    case "deleteCurrPipeline":
                        var indexToDelete = parentModule.pipelineManager.getRequestedIndex();
                        logger.info("Deleting current pipe at index " + indexToDelete);
                        int newIndex = parentModule.pipelineManager.removePipeline(indexToDelete);
                        parentModule.setPipeline(newIndex);
                        parentModule.saveAndBroadcastAll();
                        continue;
                    case "changePipeline": // change active pipeline
                        var index = (Integer) newPropValue;
                        if (index == parentModule.pipelineManager.getRequestedIndex()) {
                            logger.debug("Skipping pipeline change, index " + index + " already active");
                            continue;
                        }
                        parentModule.setPipeline(index);
                        parentModule.saveAndBroadcastAll();
                        continue;
                    case "startCalibration":
                        try {
                            var data =
                                    JacksonUtils.deserialize(
                                            (Map<String, Object>) newPropValue, UICalibrationData.class);
                            parentModule.startCalibration(data);
                            parentModule.saveAndBroadcastAll();
                        } catch (Exception e) {
                            logger.error("Error deserailizing start-cal request", e);
                        }
                        continue;
                    case "saveInputSnapshot":
                        parentModule.saveInputSnapshot();
                        continue;
                    case "saveOutputSnapshot":
                        parentModule.saveOutputSnapshot();
                        continue;
                    case "takeCalSnapshot":
                        parentModule.takeCalibrationSnapshot();
                        continue;
                    case "duplicatePipeline":
                        int idx = parentModule.pipelineManager.duplicatePipeline((Integer) newPropValue);
                        parentModule.setPipeline(idx);
                        parentModule.saveAndBroadcastAll();
                        continue;
                    case "calibrationUploaded":
                        if (newPropValue instanceof CameraCalibrationCoefficients)
                            parentModule.addCalibrationToConfig((CameraCalibrationCoefficients) newPropValue);
                        continue;
                    case "robotOffsetPoint":
                        if (currentSettings instanceof AdvancedPipelineSettings) {
                            var curAdvSettings = (AdvancedPipelineSettings) currentSettings;
                            var offsetOperation = RobotOffsetPointOperation.fromIndex((int) newPropValue);
                            var latestTarget = parentModule.lastPipelineResultBestTarget;

                            if (latestTarget != null) {
                                var newPoint = latestTarget.getTargetOffsetPoint();

                                switch (curAdvSettings.offsetRobotOffsetMode) {
                                    case Single:
                                        if (offsetOperation == RobotOffsetPointOperation.ROPO_CLEAR) {
                                            curAdvSettings.offsetSinglePoint = new Point();
                                        } else if (offsetOperation == RobotOffsetPointOperation.ROPO_TAKESINGLE) {
                                            curAdvSettings.offsetSinglePoint = newPoint;
                                        }
                                        break;
                                    case Dual:
                                        if (offsetOperation == RobotOffsetPointOperation.ROPO_CLEAR) {
                                            curAdvSettings.offsetDualPointA = new Point();
                                            curAdvSettings.offsetDualPointAArea = 0;
                                            curAdvSettings.offsetDualPointB = new Point();
                                            curAdvSettings.offsetDualPointBArea = 0;
                                        } else {
                                            // update point and area
                                            switch (offsetOperation) {
                                                case ROPO_TAKEFIRSTDUAL:
                                                    curAdvSettings.offsetDualPointA = newPoint;
                                                    curAdvSettings.offsetDualPointAArea = latestTarget.getArea();
                                                    break;
                                                case ROPO_TAKESECONDDUAL:
                                                    curAdvSettings.offsetDualPointB = newPoint;
                                                    curAdvSettings.offsetDualPointBArea = latestTarget.getArea();
                                                    break;
                                                default:
                                                    break;
                                            }
                                        }
                                        break;
                                    default:
                                        break;
                                }
                            }
                        }
                        continue;
                    case "changePipelineType":
                        parentModule.changePipelineType((Integer) newPropValue);
                        parentModule.saveAndBroadcastAll();
                        continue;
                    case "isDriverMode":
                        parentModule.setDriverMode((Boolean) newPropValue);
                        continue;
                }

                // special case for camera settables
                if (propName.startsWith("camera")) {
                    var propMethodName = "set" + propName.replace("camera", "");
                    var methods = parentModule.visionSource.getSettables().getClass().getMethods();
                    for (var method : methods) {
                        if (method.getName().equalsIgnoreCase(propMethodName)) {
                            try {
                                method.invoke(parentModule.visionSource.getSettables(), newPropValue);
                            } catch (Exception e) {
                                logger.error("Failed to invoke camera settable method: " + method.getName(), e);
                            }
                        }
                    }
                }

                try {
                    setProperty(currentSettings, propName, newPropValue);
                    logger.trace("Set prop " + propName + " to value " + newPropValue);
                } catch (NoSuchFieldException | IllegalAccessException e) {
                    logger.error(
                            "Could not set prop "
                                    + propName
                                    + " with value "
                                    + newPropValue
                                    + " on "
                                    + currentSettings
                                    + " | "
                                    + e.getClass().getSimpleName());
                } catch (Exception e) {
                    logger.error("Unknown exception when setting PSC prop!", e);
                }

                parentModule.saveAndBroadcastSelective(originContext, propName, newPropValue);
            }
            getSettingChanges().clear();
        } finally {
            changeListLock.unlock();
        }
    }

    /**
     * Sets the value of a property in the given object using reflection. This method should not be
     * used generally and is only known to be correct in the context of `onDataChangeEvent`.
     *
     * @param currentSettings The object whose property needs to be set.
     * @param propName The name of the property to be set.
     * @param newPropValue The new value to be assigned to the property.
     * @throws IllegalAccessException If the field cannot be accessed.
     * @throws NoSuchFieldException If the field does not exist.
     * @throws Exception If an some other unknown exception occurs while setting the property.
     */
    protected static void setProperty(Object currentSettings, String propName, Object newPropValue)
            throws IllegalAccessException, NoSuchFieldException, Exception {
        var propField = currentSettings.getClass().getField(propName);
        var propType = propField.getType();

        if (propType.isEnum()) {
            var actual = propType.getEnumConstants()[(int) newPropValue];
            propField.set(currentSettings, actual);
        } else if (propType.isAssignableFrom(DoubleCouple.class)) {
            var orig = (ArrayList<Number>) newPropValue;
            var actual = new DoubleCouple(orig.get(0), orig.get(1));
            propField.set(currentSettings, actual);
        } else if (propType.isAssignableFrom(IntegerCouple.class)) {
            var orig = (ArrayList<Number>) newPropValue;
            var actual = new IntegerCouple(orig.get(0).intValue(), orig.get(1).intValue());
            propField.set(currentSettings, actual);
        } else if (propType.equals(Double.TYPE)) {
            propField.setDouble(currentSettings, ((Number) newPropValue).doubleValue());
        } else if (propType.equals(Integer.TYPE)) {
            propField.setInt(currentSettings, (Integer) newPropValue);
        } else if (propType.equals(Boolean.TYPE)) {
            if (newPropValue instanceof Integer) {
                propField.setBoolean(currentSettings, (Integer) newPropValue != 0);
            } else {
                propField.setBoolean(currentSettings, (Boolean) newPropValue);
            }
        } else {
            propField.set(currentSettings, newPropValue);
        }
    }
}

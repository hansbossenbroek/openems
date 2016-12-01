package io.openems.impl.controller.symmetric.powerramp;

import java.util.List;

import io.openems.api.channel.ConfigChannel;
import io.openems.api.controller.Controller;
import io.openems.api.device.nature.ess.EssNature;
import io.openems.api.exception.InvalidValueException;
import io.openems.core.utilities.ControllerUtils;
import io.openems.core.utilities.Power;

public class PowerRampController extends Controller {

	public ConfigChannel<List<Ess>> esss = new ConfigChannel<List<Ess>>("esss", this, Ess.class);

	public ConfigChannel<Integer> pMax = new ConfigChannel<Integer>("pMax", this, Integer.class);
	public ConfigChannel<Integer> pStep = new ConfigChannel<Integer>("pStep", this, Integer.class);
	public ConfigChannel<Double> cosPhi = new ConfigChannel<Double>("cosPhi", this, Double.class);
	public ConfigChannel<Integer> sleep = new ConfigChannel<>("sleep", this, Integer.class);
	private long lastPower;
	private long lastSet;

	public PowerRampController() {
		super();
	}

	public PowerRampController(String thingId) {
		super(thingId);
	}

	@Override public void run() {
		try {
			for (Ess ess : esss.value()) {
				try {
					if (ess.gridMode.labelOptional().isPresent()
							&& ess.gridMode.labelOptional().get().equals(EssNature.OFF_GRID)) {
						lastPower = 0;
					}
					Power power = ess.power;
					if (lastSet + sleep.value() < System.currentTimeMillis()) {
						if (Math.abs(lastPower + pStep.value()) <= Math.abs(pMax.value())) {
							power.setActivePower(lastPower + pStep.value());
						} else {
							power.setActivePower(pMax.value());
						}
						lastSet = System.currentTimeMillis();
					} else {
						power.setActivePower(lastPower);
					}
					power.setReactivePower(
							ControllerUtils.calculateReactivePower(power.getActivePower(), cosPhi.value()));
					power.writePower();
					lastPower = power.getActivePower();
					log.info("Set ActivePower [" + power.getActivePower() + "] Set ReactivePower ["
							+ power.getReactivePower() + "]");
				} catch (InvalidValueException e) {
					log.error("Failed to write fixed P/Q value for Ess " + ess.id, e);
				}
			}
		} catch (InvalidValueException e) {
			log.error("No ess found.", e);
		}
	}

}

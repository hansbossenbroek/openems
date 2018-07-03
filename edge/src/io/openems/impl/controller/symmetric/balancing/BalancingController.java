/*******************************************************************************
 * OpenEMS - Open Source Energy Management System
 * Copyright (c) 2016, 2017 FENECON GmbH and contributors
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
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 *
 * Contributors:
 *   FENECON GmbH - initial API and implementation and initial documentation
 *******************************************************************************/
package io.openems.impl.controller.symmetric.balancing;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.openems.api.channel.ConfigChannel;
import io.openems.api.channel.thingstate.ThingStateChannels;
import io.openems.api.controller.Controller;
import io.openems.api.device.nature.ess.EssNature;
import io.openems.api.doc.ChannelInfo;
import io.openems.api.doc.ThingInfo;
import io.openems.api.exception.InvalidValueException;
import io.openems.core.utilities.power.symmetric.PowerException;

@ThingInfo(title = "Self-consumption optimization (Symmetric)", description = "Tries to keep the grid meter on zero. For symmetric Ess. Ess-Cluster is supported.")
public class BalancingController extends Controller {

	private final Logger log = LoggerFactory.getLogger(BalancingController.class);

	private ThingStateChannels thingState = new ThingStateChannels(this);
	/*
	 * Constructors
	 */
	public BalancingController() {
		super();
	}

	public BalancingController(String thingId) {
		super(thingId);
	}

	/*
	 * Config
	 */
	@ChannelInfo(title = "Ess", description = "Sets the Ess devices.", type = Ess.class, isArray = true)
	public final ConfigChannel<List<Ess>> esss = new ConfigChannel<List<Ess>>("esss", this);

	@ChannelInfo(title = "Grid-Meter", description = "Sets the grid meter.", type = Meter.class)
	public final ConfigChannel<Meter> meter = new ConfigChannel<Meter>("meter", this);


	/*
	 * Methods
	 */

	@Override
	public void run() {
		try {
			// Run only if at least one ess is on-grid
			List<Ess> useableEss = getUseableEss();
			if (useableEss.size() > 0) {
				// Calculate required sum values
				long calculatedPower = meter.value().activePower.value();
				long maxChargePower = 0;
				long maxDischargePower = 0;
				long useableSoc = 0;
				for (Ess ess : useableEss) {
					calculatedPower += ess.activePower.value();
					maxChargePower += ess.power.getMinP().orElse(0L);
					maxDischargePower += ess.power.getMaxP().orElse(0L);
					useableSoc += ess.useableSoc();
				}
				if (calculatedPower > 0) {
					/*
					 * Discharge
					 */
					if (calculatedPower > maxDischargePower) {
						calculatedPower = maxDischargePower;
					}
					// sort ess by useableSoc asc
					Collections.sort(useableEss, (a, b) -> {
						try {
							return (int) (a.useableSoc() - b.useableSoc());
						} catch (InvalidValueException e) {
							log.error(e.getMessage());
							return 0;
						}
					});
					for (int i = 0; i < useableEss.size(); i++) {
						Ess ess = useableEss.get(i);
						// calculate minimal power needed to fulfill the calculatedPower
						long minP = calculatedPower;
						for (int j = i + 1; j < useableEss.size(); j++) {
							if (useableEss.get(j).useableSoc() > 0) {
								minP -= useableEss.get(j).power.getMaxP().orElse(0L);
							}
						}
						if (minP < 0) {
							minP = 0;
						}
						// check maximal power to avoid larger charges then calculatedPower
						long maxP = ess.power.getMaxP().orElse(0L);
						if (calculatedPower < maxP) {
							maxP = calculatedPower;
						}
						double diff = maxP - minP;
						/*
						 * weight the range of possible power by the useableSoc
						 * if the useableSoc is negative the ess will be charged
						 */
						long p = (long) (Math.ceil((minP + diff / useableSoc * ess.useableSoc()) / 100) * 100);
						ess.limit.setP(p);
						ess.power.applyLimitation(ess.limit);
						calculatedPower -= p;
					}
				} else {
					/*
					 * Charge
					 */
					if (calculatedPower < maxChargePower) {
						calculatedPower = maxChargePower;
					}
					/*
					 * sort ess by 100 - useabelSoc
					 * 100 - 90 = 10
					 * 100 - 45 = 55
					 * 100 - (- 5) = 105
					 * => ess with negative useableSoc will be charged much more then one with positive useableSoc
					 */
					Collections.sort(useableEss, (a, b) -> {
						try {
							return (int) ((100 - a.useableSoc()) - (100 - b.useableSoc()));
						} catch (InvalidValueException e) {
							log.error(e.getMessage());
							return 0;
						}
					});
					for (int i = 0; i < useableEss.size(); i++) {
						Ess ess = useableEss.get(i);
						// calculate minimal power needed to fulfill the calculatedPower
						long minP = calculatedPower;
						for (int j = i + 1; j < useableEss.size(); j++) {
							minP -= useableEss.get(j).power.getMinP().orElse(0L);
						}
						if (minP > 0) {
							minP = 0;
						}
						// check maximal power to avoid larger charges then calculatedPower
						long maxP = ess.power.getMinP().orElse(0L);
						if (calculatedPower > maxP) {
							maxP = calculatedPower;
						}
						double diff = maxP - minP;
						// weight the range of possible power by the useableSoc
						long p = (long) Math.floor(
								(minP + diff / (useableEss.size() * 100 - useableSoc) * (100 - ess.useableSoc())) / 100)
								* 100;
						ess.limit.setP(p);
						ess.power.applyLimitation(ess.limit);
						calculatedPower -= p;
					}
				}

			}
		} catch (InvalidValueException e) {
			log.error(e.getMessage());
		} catch (PowerException e) {
			log.error("Failed to set Power!",e);
		}
	}

	private List<Ess> getUseableEss() throws InvalidValueException {
		List<Ess> useableEss = new ArrayList<>();
		for (Ess ess : esss.value()) {
			if (ess.gridMode.valueOptional().isPresent()
					&& ess.gridMode.labelOptional().equals(Optional.of(EssNature.ON_GRID))) {
				useableEss.add(ess);
			}
		}
		return useableEss;
	}

	@Override
	public ThingStateChannels getStateChannel() {
		return this.thingState;
	}

}
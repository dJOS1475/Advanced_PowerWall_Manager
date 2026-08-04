/**
 *  Fronius Solar Inverter Driver for Hubitat
 *
 *	Copyright 2024 Derek Osborn
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License. You may obtain a copy of the License at:
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 *  on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 *  for the specific language governing permissions and limitations under the License.
 *
 *  EDITED by jchurch to include runEvery15Minutes(refresh) - 02/20
 *  EDITED by jchurch and Markus to include ErrorCode information and clean up ported code for Hubitat slightly - 02/20
 *  EDITED by jchurch and Markus to include kWh conversation for energy stat - 02/21
 *  Updates by HardyM to give 1 min updates, reduce to 30 mins polls overnight, and also return data from Fronius SmartMeter to give Grid power and Load. 2021-07-25
 *  Dec 18,2021.  Added attribute access to pGrid and pLoad. (HardyM)
 *  Jan 22,2024 - Updated to work with latest API (Sébastien Viel & Stephen Townsend)
 *
 *  May 2024 - Forked by Derek Osborn 
 *	v2.0.0 - Updated to convert Watt hours to Kilowatt hours, Added Dashboard tile, and published to HPM for easier access
 *  v2.1.0 - Converted Lifetime Energy to Megawatt hours for improved legibility 
 *  v2.2.0 - I realised I could have reused some existing functions, so I've rewritten a few bits to make the code more efficient. I also changed generation for this year to MWh's as this number can get pretty big too.
 *  v2.2.1 - added a 250ms delay to the tile generation to ensure all data is updated first
 *  v2.3.0 - added GEN24 Inverter compatibility mode
 *  v2.4.0 - Bug fixes and improvements: fixed textVersion(), improved error handling, added input validation, fixed compatMode HTML tile issues, 
 *           added constants, improved null safety, enhanced logging, made schedules configurable
 *  
 *  Source:
 *  https://github.com/dJOS1475/Fronius_Inverter_Driver_Hubitat
 *
 *  Forked from:
 *  https://github.com/SebastienViel/FroniusInverter/tree/main
 *
 */
 
import groovy.json.JsonSlurper
import java.math.BigDecimal

def version() {
    return "2.4.0"
}

// Constants as helper methods (Hubitat doesn't support script-level constants)
private Integer getWattsToKw() { return 1000 }
private Integer getWattsToMw() { return 1000000 }
private Integer getMaxParseFailures() { return 3 }
private String getDefaultNormalSchedule() { return '0 */1 * ? * *' }
private String getDefaultOfflineSchedule() { return '0 */30 * ? * *' }

preferences {
	input name: "about", type: "paragraph", element: "paragraph", title: "Fronius Solar Inverter Driver", description: "v${version()}"
	input name: "inverterNumber", type: "number", title: "Inverter Number", description: "The Inverter Number (typically 1)", required: true, displayDuringSetup: true, defaultValue: 1
	input name: "destIp", type: "text", title: "IP Address", description: "The device IP address (e.g., 192.168.1.100)", required: true, displayDuringSetup: true
	input name: "destPort", type: "number", title: "Port", description: "The port you wish to connect (default: 80)", required: true, displayDuringSetup: true, defaultValue: 80
	input name: "compatMode", type: "bool", title: "GEN24 Compatibility Mode", description: "Enable for GEN24 inverters (disables daily/yearly energy tracking)", required: true, defaultValue: false
	input name: "normalPollSchedule", type: "text", title: "Normal Poll Schedule", description: "Cron expression for normal polling (default: every 1 minute)", defaultValue: getDefaultNormalSchedule()
	input name: "offlinePollSchedule", type: "text", title: "Offline Poll Schedule", description: "Cron expression for offline polling (default: every 30 minutes)", defaultValue: getDefaultOfflineSchedule()
	input name: "logDebugEnable", type: "bool", title: "Enable Debug Logging", required: true, defaultValue: false
	input name: "logInfoEnable", type: "bool", title: "Enable Info Logging", required: true, defaultValue: true
}

metadata {
	definition (
		name: "Fronius Solar Inverter",
		namespace: "dJOS",
		author: "Derek Osborn",
		importUrl:"https://raw.githubusercontent.com/dJOS1475/Fronius_Inverter_Driver_Hubitat/main/Fronius_Solar_Inverter.groovy"
	) {
		capability "Polling"
		capability "PowerMeter"
		capability "EnergyMeter"
		capability "Actuator"
		capability "Refresh"
		capability "Sensor"
		capability "VoltageMeasurement"
		
		attribute "errorCode", "number"
		attribute "pGrid", "number"
		attribute "pLoad", "number"
		attribute "TotalEnergy", "number"
		attribute "YearValue", "number"
		attribute "DayValue", "number"
		attribute "eYear", "number"
		attribute "power", "number"
		attribute "energy", "number"
		attribute "healthStatus", "enum", ["unknown", "offline", "online"]
		attribute "htmlEnergy", "string"
	}
}

def installed() {
	logInfo "Fronius Inverter driver installed - ${textVersion()} ${textCopyright()}"
	initialize()
}

def initialize() {
	logInfo "Initializing Fronius Inverter ${textVersion()}"
	
	// Validate inputs
	if (!validateInputs()) {
		log.error "Invalid configuration - please check settings"
		return
	}
	
	// Initialize attributes
	sendEvent(name: "power", value: 0, unit: "W")
	sendEvent(name: "energy", value: 0, unit: "kWh")
	sendEvent(name: "eYear", value: 0, unit: "MWh")
	sendEvent(name: "TotalEnergy", value: 0, unit: "MWh")
	sendEvent(name: "pGrid", value: 0, unit: "W")
	sendEvent(name: "pLoad", value: 0, unit: "W")
	sendEvent(name: "errorCode", value: 0)
	sendEvent(name: "healthStatus", value: "unknown")
	
	// Initialize state
	state.parseCallCounter = 0
	
	// Start polling
	updated()
}

def validateInputs() {
	def valid = true
	
	// Validate IP address
	if (!destIp || !destIp.matches(/^(\d{1,3}\.){3}\d{1,3}$/)) {
		log.error "Invalid IP address format: ${destIp}"
		valid = false
	}
	
	// Validate port
	if (!destPort || destPort < 1 || destPort > 65535) {
		log.error "Invalid port number: ${destPort}"
		valid = false
	}
	
	// Validate inverter number
	if (!inverterNumber || inverterNumber < 0) {
		log.error "Invalid inverter number: ${inverterNumber}"
		valid = false
	}
	
	return valid
}

// parse events into attributes
def parse(String description) {	
	def msg = parseLanMessage(description)
	def slurper = new JsonSlurper()
	
	try {
		def result = slurper.parseText(msg.body)
		
		// Check for errors
		def errorCode = result?.Body?.Data?."${inverterNumber}"?.ErrorCode
		if (errorCode != null) {
			sendEvent(name: "errorCode", value: errorCode)
			logDebug "Inverter error code: ${errorCode}"
		} else {
			sendEvent(name: "errorCode", value: 0)
			
			// Process data based on compatibility mode
			if (result?.Body?.Data?.Site) {
				logDebug "Received data from inverter: ${result.Body.Data.Site}"
				processInverterData(result.Body.Data.Site)
				
				// Update HTML Tile
				runIn(1, htmlTile)  // Use runIn instead of pauseExecution
				
				// Update health status
				if (state.parseCallCounter > 0) {
					updated()  // Reset the poll timer back to normal if we got an answer
					sendEvent(name: "healthStatus", value: "online")
				}
				state.parseCallCounter = 0
			} else {
				log.warn "No site data found in response"
			}
		}
	} catch (Exception e) {
		log.error "Error parsing inverter response: ${e.message}"
		logDebug "Response body: ${msg.body}"
	}
}

private void processInverterData(Map siteData) {
	try {
		// Extract power values with null safety
		def pGridValue = siteData.P_Grid
		Integer pGrid = pGridValue != null ? pGridValue.toInteger() : 0
		
		def pLoadValue = siteData.P_Load
		Integer pLoad = pLoadValue != null ? Math.abs(pLoadValue.toInteger()) : 0  // Make positive
		
		def pPV = siteData.P_PV
		Integer power = pPV != null ? pPV.toInteger() : 0
		
		// Send power events
		sendEvent(name: "power", value: power, unit: "W")
		sendEvent(name: "pGrid", value: pGrid, unit: "W")
		sendEvent(name: "pLoad", value: pLoad, unit: "W")
		
		// Process energy values based on compatibility mode
		if (compatMode) {
			// GEN24 mode - only total energy available
			Integer totalValue = siteData.E_Total ?: 0
			BigDecimal totalMWh = new BigDecimal(totalValue / getWattsToMw()).setScale(2, BigDecimal.ROUND_HALF_UP)
			sendEvent(name: "TotalEnergy", value: totalMWh, unit: "MWh")
			
			// Set daily and yearly to 0 in compat mode
			sendEvent(name: "energy", value: 0, unit: "kWh")
			sendEvent(name: "eYear", value: 0, unit: "MWh")
			
			logDebug "GEN24 Mode - Power: ${power}W, Total: ${totalMWh}MWh, Grid: ${pGrid}W, Load: ${pLoad}W"
		} else {
			// Standard mode - all energy data available
			Integer yearValue = siteData.E_Year ?: 0
			Integer dayValue = siteData.E_Day ?: 0
			Integer totalValue = siteData.E_Total ?: 0
			
			BigDecimal dayKWh = new BigDecimal(dayValue / getWattsToKw()).setScale(2, BigDecimal.ROUND_HALF_UP)
			BigDecimal yearMWh = new BigDecimal(yearValue / getWattsToMw()).setScale(2, BigDecimal.ROUND_HALF_UP)
			BigDecimal totalMWh = new BigDecimal(totalValue / getWattsToMw()).setScale(2, BigDecimal.ROUND_HALF_UP)
			
			sendEvent(name: "energy", value: dayKWh, unit: "kWh")
			sendEvent(name: "eYear", value: yearMWh, unit: "MWh")
			sendEvent(name: "TotalEnergy", value: totalMWh, unit: "MWh")
			
			logDebug "Standard Mode - Power: ${power}W, Day: ${dayKWh}kWh, Year: ${yearMWh}MWh, Total: ${totalMWh}MWh, Grid: ${pGrid}W, Load: ${pLoad}W"
		}
	} catch (Exception e) {
		log.error "Error processing inverter data: ${e.message}"
	}
}

def htmlTile() {
	try {
		String htmlEnergy = "<div style='line-height:1.0; font-size:0.75em;'><br>Solar Energy Produced:<br></div>"
		htmlEnergy += "<div style='line-height:50%;'><br></div>"
		
		// Only show daily and yearly stats if not in compat mode
		if (!compatMode) {
			def energyToday = device.currentValue('energy') ?: 0
			def energyYear = device.currentValue('eYear') ?: 0
			
			htmlEnergy += "<div style='line-height:1.0; font-size:0.75em;'><br>Today: ${energyToday} kWh<br></div>"
			htmlEnergy += "<div style='line-height:50%;'><br></div>"
			htmlEnergy += "<div style='line-height:1.0; font-size:0.75em;'><br>This Year: ${energyYear} MWh<br></div>"
		}
		
		def energyTotal = device.currentValue('TotalEnergy') ?: 0
		htmlEnergy += "<div style='line-height:1.0; font-size:0.75em;'><br>Lifetime: ${energyTotal} MWh<br></div>"
		
		sendEvent(name: "htmlEnergy", value: htmlEnergy)
		logDebug "HTML tile updated (${htmlEnergy.length()} characters)"
	} catch (Exception e) {
		log.error "Error generating HTML tile: ${e.message}"
	}
}

// handle commands
def poll() {
	if (state.parseCallCounter == null) {
		state.parseCallCounter = 1
	} else {
		state.parseCallCounter++
	}
	
	if (state.parseCallCounter >= getMaxParseFailures()) {
		// Not getting Fronius's replies so could be sleeping. Set the timer with longer interval
		logInfo "Inverter appears offline - switching to reduced polling schedule"
		sendEvent(name: "healthStatus", value: "offline")
		unschedule(refresh)
		def offlineSchedule = offlinePollSchedule ?: getDefaultOfflineSchedule()
		schedule(offlineSchedule, refresh)
	}
	
	callInvertor()
}

def callInvertor() {
	try {
		if (!validateInputs()) {
			log.error "Cannot call inverter - invalid configuration"
			return null
		}
		
		def hosthex = convertIPtoHex(destIp)
		def porthex = convertPortToHex(destPort)
		device.deviceNetworkId = "$hosthex:$porthex" 

		// Get inverter info
		sendHubCommand(new hubitat.device.HubAction(
			'method': 'GET',
			'path': "/solar_api/v1/GetInverterInfo.cgi?Scope=System",
			'headers': [HOST: "$destIp:$destPort"]
		))

		// Get real-time power flow data
		def hubAction = new hubitat.device.HubAction(
			'method': 'GET',
			'path': "/solar_api/v1/GetPowerFlowRealtimeData.fcgi",
			'headers': [HOST: "$destIp:$destPort"]
		)
		
		logDebug "Calling inverter at ${destIp}:${destPort}"
		return hubAction
	}
	catch (Exception e) {
		log.error "Error calling inverter: ${e.message}"
		return null
	}
}

private String textVersion() {
	return "v${version()}"
}

private String textCopyright() {
	return "Copyright © 2024 Derek Osborn"
}

private String convertIPtoHex(ipAddress) {
	try {
		String hex = ipAddress.tokenize('.').collect {  
			String.format('%02X', it.toInteger()) 
		}.join()
		return hex
	} catch (Exception e) {
		log.error "Error converting IP to hex: ${e.message}"
		return ""
	}
}

private String convertPortToHex(port) {
	try {
		String hexport = String.format('%04X', port.toInteger())
		return hexport
	} catch (Exception e) {
		log.error "Error converting port to hex: ${e.message}"
		return ""
	}
}

def refresh() {
	logDebug "Refresh called"
	poll()
}

def updated() {
	logInfo "Settings updated"
	
	// Validate inputs
	if (!validateInputs()) {
		log.error "Invalid configuration detected"
		return
	}
	
	// Reset scheduling
	unschedule(refresh)
	def normalSchedule = normalPollSchedule ?: getDefaultNormalSchedule()
	schedule(normalSchedule, refresh)
	
	// Reset parse counter
	state.parseCallCounter = 0
	
	// Disable debug logging after 30 minutes
	if (logDebugEnable) {
		runIn(1800, disableDebugLogging)
	}
	
	logInfo "Polling scheduled with: ${normalSchedule}"
}

def disableDebugLogging() {
	logInfo "Automatically disabling debug logging after 30 minutes"
	device.updateSetting("logDebugEnable", [value: "false", type: "bool"])
}

// Logging helpers
private void logDebug(String msg) {
	if (logDebugEnable) {
		log.debug msg
	}
}

private void logInfo(String msg) {
	if (logInfoEnable) {
		log.info msg
	}
}

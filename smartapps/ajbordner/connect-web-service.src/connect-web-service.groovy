/**
 *  Connect Web Service
 *
 *  Copyright 2015 linkAges Connect
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
 */
definition(
    name: "Connect Web Service",
    namespace: "ajbordner",
    author: "linkAges Connect",
    description: "Web Service for downloading the sensor event history for Connect users",
    category: "Health & Wellness",
    iconUrl: "https://s3.amazonaws.com/smartapp-icons/Convenience/Cat-Convenience.png",
    iconX2Url: "https://s3.amazonaws.com/smartapp-icons/Convenience/Cat-Convenience@2x.png",
    iconX3Url: "https://s3.amazonaws.com/smartapp-icons/Convenience/Cat-Convenience@2x.png",
    oauth: [displayName: "Palo Alto Medical Foundation", displayLink: "http://connect.linkages.org"])

preferences {
  section ("Allow linkAges Connect to access these things...") {
    input "switches", "capability.powerMeter", title: "Outlet", multiple: true, required: true
    input "acceleration", "capability.accelerationSensor", title: "Accelerometer", multiple: true, required: true
    input "contact", "capability.contactSensor", title: "Contact sensor", multiple: true, required: true
    input "motion", "capability.motionSensor", title: "Motion sensor", multiple: true, required: true
    input "water", "capability.waterSensor", title: "Water sensor", multiple: true, required: false
    input "presence", "capability.presenceSensor", title: "Presence sensor", multiple: true, required: false
    input "batteries", "capability.battery", title: "Batteries", multiple: true, required: false
  }
}

mappings {
   path("/getHistory") {
    action: [
      GET: "listEvents"
    ]
  }
   path("/setPowerCutoff") {
    action: [
      GET: "setPowerCutoff"
    ]
  }
   path("/getPowerCutoff") {
    action: [
     GET: "getPowerCutoff"
    ]
  }
   path("/pushEvents") {   // manually push event data
    action: [
     GET: "postEventsHandler"
    ]
  }
  path("/setUpdateInterval") {
    action: [
     GET: "setUpdateInterval"
    ]
  }
  path("/getUpdateInterval") {
    action: [
     GET: "getUpdateInterval"
    ]
  }
  path("/getBatteryLevels") {
    action: [
     GET: "listBatteries"
    ]
  }
  path("/getStateVars") {
    action: [
     GET: "getStateVars"
    ]
  }
  path("/getLatestEventTimes") {
    action: [
     GET: "getLatestEventTimes"
    ]
  }
  path("/getAppName") {
    action: [
     GET: "getAppName"
    ]
  }
  path("/resetData") {
    action: [
     GET: "resetData"
    ]
  }
  path("/getDataSize") {
    action: [
     GET: "getDataSize"
    ]
  }
  path("/getHubID") {
    action: [
     GET: "getHubID"
    ]
  }
}

def listEvents() {
	def maxNumEvents = 99999
    def startDate = new Date().parse("yyyy-MM-dd", params?.startDate)
    // event data for required sensors
 	def allEvents = contact.eventsSince(startDate, [max: maxNumEvents]) + motion.eventsSince(startDate, [max: maxNumEvents]) + switches.eventsSince(startDate, [max: maxNumEvents])
	// event data for optional sensors
	if (water != null) {
    	allEvents = allEvents + water.eventsSince(startDate, [max: maxNumEvents])
    }
    if (presence != null) {
    	allEvents = allEvents + presence.eventsSince(startDate, [max: maxNumEvents])
    }
   	def rawData = []
    allEvents.each { dev ->
    	dev.each {
        //log.debug "name = ${it.device?.name}"
        //log.debug "label = ${it.device?.label}"
        //log.debug "event_type = ${it?.name}"
        //log.debug "value = ${it?.value}"
        //log.debug "date = ${it?.isoDate}"
        if (it?.name != null && it?.name != "temperature") {
        	rawData << [name: it.device?.name, label : it.device?.label, event_type: it?.name, value: it?.value, date: it?.isoDate]
         }
    		//rawData << [label: it?.displayName, name: it?.name, value: it?.value, date: it?.isoDate]
        }
    }
    def resp = ["hub_id": motion.hub.zigbeeId[0], "location": location.name, "raw": rawData]
    return resp
}

def listBatteries() {
	return ["hub_id": motion.hub.zigbeeId[0], "location": location.name,"info": batteries?.collect{[id: it.id, name: it.name, label: it.label, status: it.currentValue('battery')]}?.sort{it.name}]
}

def setPowerCutoff() {
	state.powerCutoff = Float.parseFloat(params?.watts)
    return [watts: state.powerCutoff]
}

def getPowerCutoff() {
	return [watts: state.powerCutoff]
}

def getStateVars() {
	return [state: state]
}

def getAppName() {
	return [name: app.label]
}

def resetData() {
    state.evtData = []
    state.rawEvtData = []
}

def getDataSize() {
	return [processed: state.evtData.size(), raw: state.rawEvtData.size(), total: state.toString().length()]
}

def getHubID() {
   return [hub_id: motion?.hub?.zigbeeId[0]]
}

def installed() {
	log.debug "Installed with settings: ${settings}"

	initialize()
}

def updated() {
	log.debug "Updated with settings: ${settings}"

	unsubscribe()
	initialize()
}

def initialize() {
	subscribe(switches, "power", switchHandler)
    subscribe(acceleration, "acceleration", accelerationHandler)
    subscribe(contact, "contact", contactHandler)
    subscribe(motion, "motion", motionHandler)
    subscribe(water, "water", waterHandler)
    subscribe(presence, "presence", presenceHandler)
    state.uri = "https://connect.linkages.org/smartthings.php"
    state.versionDate = "2016-02-25"
    state.maxStateSize = 80000  // maximum state size is 100,000 bytes so do not append when current size is > state.maxStateSize
    state.evtData = []
    state.rawEvtData = []
    state.maxPower = 0
    state.powerOn = null
    state.powerChangeTime = (new Date()).time
    state.powerCutoff = 5.0
    state.activeState = ['Motion Sensor': null, 'Multipurpose A': null, 'Multipurpose B': null, 'Arrival Sensor': null]
    state.changeTime = ['Motion Sensor': (new Date()).time, 'Multipurpose A': (new Date()).time, 'Multipurpose B': (new Date()).time, 'Outlet': (new Date()).time, 'Arrival Sensor': (new Date()).time]
    state.lastActivity = ['Motion Sensor': [null, 'inactive'], 'Multipurpose A contact': [null, 'closed'], 'Multipurpose A acceleration': [null, 'active'], 'Multipurpose B contact': [null, 'closed'], 'Multipurpose B acceleration': [null, 'inactive'], 'Arrival Sensor': [null, 'not_present']]
    state.delay = ['Motion Sensor': 30, 'Multipurpose A': 60, 'Multipurpose B': 60, 'Outlet': 60, 'Arrival Sensor': 60]
    state.cronMinutes = 1  // every minute
    state.cronSeconds = (new Random()).nextInt(60);
    schedule("${state.cronSeconds} 0/${state.cronMinutes} * * * ?", postEventsHandler)
}

def setUpdateInterval() {
  state.cronMinutes = params?.minutes.toInteger()
  unschedule()
  schedule("${state.cronSeconds} 0/${state.cronMinutes} * * * ?", postEventsHandler)
}

def getUpdateInterval() {
  return [minutes: state.cronMinutes, seconds: state.cronSeconds]
}

// Send events and clear if state size is near limit
def checkStateSize() {
  if (state.toString().length() > state.maxStateSize) {
    postEventsHandler()  // send events and clear
  }
}

def addRawEvent(evt) {
  if (evt.name == "temperature") {
  	return []
  }
 def now = (new Date()).time 
 def value = evt.value
  if (evt.name == "power")  {
    def timeSinceChange = (now - state.changeTime['Outlet'])/1000.0
    state.maxPower = Math.max(state.maxPower, Float.parseFloat(evt.value))
    //log.debug "timeSinceChange = ${timeSinceChange}"
    if (timeSinceChange >= state.delay['Outlet']) {
      	value = sprintf("%.1f",state.maxPower)
      	//log.debug "OUTLET: ${state.changeTime['Outlet']}, ${now}, ${timeSinceChange}, ${value} watts"
     	state.changeTime['Outlet'] = now
        state.maxPower = 0
     }
  }
  state.rawEvtData << [name: evt.device?.name, label : evt.device.label, event_type: evt.name, value: value, date: evt.isoDate]
  checkStateSize()
}

def addEvent(evt) {
  //log.debug "addEvent: ${evt.device?.name}, ${evt.name}, ${evt.value}"
  state.evtData << [name: evt.device?.name, label : evt.device.label, event_type: evt.name, value: evt.value, date: evt.isoDate, duration: null]
  checkStateSize()	
}

def addOutletEvent(evt, eventValue, duration) {
  state.powerChangeTime = (new Date()).time
  def intDuration = duration.setScale(0, BigDecimal.ROUND_HALF_UP)
  state.evtData << [name: evt.device?.name, label : evt.device.label, event_type: evt.name, value: eventValue, date: evt.isoDate, duration: intDuration]
  checkStateSize()
}

def checkForActivity(sensor, sensorAttribute, requirePersistantInactivation = true, requirePersistantActivation = false, activeStateName = "active", inactiveStateName = "inactive") {
    // Require state.delay seconds before changing sensor activity state in order to group together clusters of short events
    // If requirePersistantInactivation = true (default) then require state.delay seconds before switching from active to inactive state, otherwise switch immediately.
    // Similarly, for requirePersistantActivation
    def now = (new Date()).time
    def latestActivityState = sensor.latestState(sensorAttribute);
    def currentActivityState = sensor.currentState(sensorAttribute);
    for (def sensorNum = 0; sensorNum < sensor.name.size(); sensorNum++) {
      def latestTime = Date.parse("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", latestActivityState.dateCreated[sensorNum])
      def timeSinceStateChange = (now - latestTime.time)/1000.0   // time since last state change in seconds
      def sensorName = sensor.name[sensorNum]
      //log.debug "${sensorName}: ${state.activeState[sensorName]}, ${timeSinceStateChange}; ${state.activeState.inspect()}; ${state.changeTime.inspect()}"
      //log.debug "sensorName = ${sensorName}, activeState = ${state.activeState[sensorName]} , currentActivityState = ${currentActivityState.value[sensorNum]}, timeSinceStateChange = ${timeSinceStateChange}"
      if (state.activeState[sensorName] == null) {
      	//log.debug "${sensorName} NULL"
    	state.activeState[sensorName] = (currentActivityState.value[sensorNum] == activeStateName) 
      } else {
    	  if (state.activeState[sensorName] && (currentActivityState.value[sensorNum] == inactiveStateName) && (!requirePersistantInactivation || (timeSinceStateChange >= state.delay[sensorName]))) {
            state.activeState[sensorName] = false
            def duration = (now - state.changeTime[sensorName])/1000.0 - timeSinceStateChange
            state.changeTime[sensorName] = now
            //log.debug "${sensorName} INACTIVE ${duration.setScale(0, BigDecimal.ROUND_HALF_UP)}"
    	    state.evtData << [name: sensorName, label: sensor.label[sensorNum], event_type: sensorAttribute, value: inactiveStateName, date: latestActivityState.dateCreated[sensorNum], duration: duration.setScale(0, BigDecimal.ROUND_HALF_UP)]
    	} else if (!state.activeState[sensorName] && (currentActivityState.value[sensorNum] == activeStateName) && (!requirePersistantActivation || (timeSinceStateChange >= state.delay[sensorName]))) {
            state.activeState[sensorName] = true
            def duration = (now - state.changeTime[sensorName])/1000.0            
            state.changeTime[sensorName] = now
            //log.debug "${sensorName} ACTIVE ${duration.setScale(0, BigDecimal.ROUND_HALF_UP)}"
    	    state.evtData << [name: sensorName, label: sensor.label[sensorNum], event_type: sensorAttribute, value: activeStateName, date: latestActivityState.dateCreated[sensorNum], duration: duration.setScale(0, BigDecimal.ROUND_HALF_UP)]
    	}
    }
  }
  checkStateSize()
}

def getLatestEventTimes() {
	def resp = [(switches.name[0]): [switches.latestState("power").dateCreated[0], switches.latestState("power").value[0]]]
    for (dev in state.lastActivity) {
    	resp << dev
    }
	return resp
}

def postEventsHandler() {
    checkForActivity(motion, "motion")
    checkForActivity(acceleration,"acceleration")
    if (presence != null) {
    	checkForActivity(presence, "presence", true, false, "present", "not present")
     }
     // Push if any processed event data
     def json = new groovy.json.JsonBuilder(["hub_id": motion.hub.zigbeeId[0], "location": location.name, "versionDate": state.versionDate, "processed": state.evtData, "raw": state.rawEvtData]).toString()
    //log.debug("JSON: ${json}")
    def params = [
   	uri: state.uri,
    	body: json
   	]
    try {
        httpPostJson(params)
    } catch ( groovyx.net.http.HttpResponseException ex ) {
       	log.debug "Unexpected response error for Connect production: ${ex.statusCode}"
    }
    state.evtData = []   // clear processed event data
    state.rawEvtData = []  // clear raw event data
}

def switchHandler(evt) {
	if (evt.name == "power") {
    	def watts = Float.parseFloat(evt.value)
    	if (state.powerOn == null) {
        	state.powerOn = (watts >= state.powerCutoff)
        } else {
        	def now = new Date()
            def duration = (now.time - state.powerChangeTime)/1000.0  // time since last state change in seconds
 			if (state.powerOn && (watts < state.powerCutoff)) {
    			state.powerOn = false
            	addOutletEvent(evt, "inactive",duration)
  			} else if (!state.powerOn && (watts >= state.powerCutoff)) {
        		state.powerOn = true
            	addOutletEvent(evt, "active",duration)
      		}
       }
    }
    addRawEvent(evt)
}

def accelerationHandler(evt) {
    checkForActivity(acceleration, "acceleration")
    addRawEvent(evt)
    if (evt.value == "active") {
    	state.lastActivity["${evt.device?.name} acceleration"] = [new Date().format("yyyy-MM-dd'T'HH:mm:ss'Z'", TimeZone.getTimeZone("UTC")), evt.value]
    }
}

def contactHandler(evt) {
    addEvent(evt)
    addRawEvent(evt)
    if (evt.value == "closed") {
    	state.lastActivity["${evt.device?.name} contact"] = [new Date().format("yyyy-MM-dd'T'HH:mm:ss'Z'", TimeZone.getTimeZone("UTC")), evt.value]
    }
}

def motionHandler(evt) {
    checkForActivity(motion, "motion")
    addRawEvent(evt)
    if (evt.value == "active") {
    	state.lastActivity[evt.device?.name] = [new Date().format("yyyy-MM-dd'T'HH:mm:ss'Z'", TimeZone.getTimeZone("UTC")), evt.value]
    }
}

def waterHandler(evt) {
    addEvent(evt)
    addRawEvent(evt)
    state.lastActivity[evt.device?.name] = [new Date().format("yyyy-MM-dd'T'HH:mm:ss'Z'", TimeZone.getTimeZone("UTC")), evt.value]
}

def presenceHandler(evt) {
    checkForActivity(presence, "presence", true, false, "present", "not present")
    addRawEvent(evt)
    state.lastActivity[evt.device?.name] = [new Date().format("yyyy-MM-dd'T'HH:mm:ss'Z'", TimeZone.getTimeZone("UTC")), evt.value]
}

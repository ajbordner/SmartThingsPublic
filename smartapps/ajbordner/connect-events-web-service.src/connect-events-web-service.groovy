/**
 *  Connect Events Web Service
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
    name: "Connect Events Web Service",
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
}

def listEvents() {
	def maxNumEvents = 99999
    def startDate = new Date().parse("yyyy-MM-dd", params?.startDate)
    def endDate = new Date().parse("yyyy-MM-dd", params?.endDate)
 	def allEvents = contact.eventsBetween(startDate, endDate, [max: maxNumEvents]) + motion.eventsBetween(startDate, endDate, [max: maxNumEvents]) + switches.eventsBetween(startDate, endDate, [max: maxNumEvents])
   	def resp = []
    allEvents.each { dev ->
    	dev.each {
    		resp << [label: it.displayName, name: it.name, value: it.value, date: it.isoDate]
        }
    }
    return resp
}

def listBatteries() {
	return ["hub_id": location.hubs[0].id, "location": location.name,"info": batteries?.collect{[id: it.id, name: it.name, label: it.label, status: it.currentValue('battery')]}?.sort{it.name}]
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

def installed() {
	log.debug "Installed for hub ${location.hubs[0].id} with settings: ${settings}"

	initialize()
}

def updated() {
	log.debug "Updated for hub ${location.hubs[0].id} with settings: ${settings}"

	unsubscribe()
	initialize()
}

def initialize() {
	subscribe(switches, "power", switchHandler)
    subscribe(acceleration, "acceleration", accelerationHandler)
    subscribe(contact, "contact", contactHandler)
    subscribe(motion, "motion", motionHandler)
    subscribe(water, "water", waterHandler)
    state.evtData = []
    state.rawEvtData = []
    state.powerOn = null
    state.powerChangeTime = (new Date()).time
    state.powerCutoff = 5.0
    state.activeState = ['Motion Sensor': null, 'Multipurpose A': null, 'Multipurpose B': null]
    state.changeTime = ['Motion Sensor': (new Date()).time, 'Multipurpose A': (new Date()).time,'Multipurpose B': (new Date()).time]
    state.delay = ['Motion Sensor': 60, 'Multipurpose A': 60, 'Multipurpose B': 60]
    state.cronMinutes = 1  // every minute
    schedule("0 0/${state.cronMinutes} * * * ?",postEventsHandler)
}

def setUpdateInterval() {
  state.cronMinutes = params?.minutes.toInteger()
  unschedule()
  schedule("0 0/${state.cronMinutes} * * * ?",postEventsHandler)
}

def getUpdateInterval() {
  return [minutes: state.cronMinutes]
}

def addRawEvent(evt) {
	state.rawEvtData << [name: evt.device.name, label : evt.device.label, event_type: evt.name, value: evt.value, date: evt.isoDate]
}

def addEvent(evt) {
	//log.debug "addEvent: ${evt.device.name}, ${evt.name}, ${evt.value}"
	state.evtData << [name: evt.device.name, label : evt.device.label, event_type: evt.name, value: evt.value, date: evt.isoDate, duration: null]
}

def addOutletEvent(evt, eventValue, duration) {
  state.powerChangeTime = (new Date()).time
  def intDuration = duration.setScale(0, BigDecimal.ROUND_HALF_UP)
  state.evtData << [name: evt.device.name, label : evt.device.label, event_type: evt.name, value: eventValue, date: evt.isoDate, duration: intDuration]
}

def checkForActivity(sensor, sensorAttribute) {
	// Only change sensor activity if persists for longer than state.delay seconds
    def now = (new Date()).time
    def latestActivityState = sensor.latestState(sensorAttribute);
    def currentActivityState = sensor.currentState(sensorAttribute);
    for (def sensorNum = 0; sensorNum < sensor.name.size(); sensorNum++) {
      def latestTime = Date.parse("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",latestActivityState.dateCreated[sensorNum])
      def timeSinceStateChange = (now - latestTime.time)/1000.0   // time since last state change in seconds
      def sensorName = sensor.name[sensorNum]
      //log.debug "${sensorName}: ${state.activeState[sensorName]}, ${timeSinceStateChange}; ${state.activeState.inspect()}; ${state.changeTime.inspect()}"
      if (state.activeState[sensorName] == null) {
    	state.activeState[sensorName] = (currentActivityState.value[sensorNum] == "active")
      } else {
    	  if (state.activeState[sensorName] && (currentActivityState.value[sensorNum] == "inactive") && (timeSinceStateChange >= state.delay[sensorName])) {
            state.activeState[sensorName] = false
            def duration = (now - state.changeTime[sensorName])/1000.0 - timeSinceStateChange
            state.changeTime[sensorName] = now
            //log.debug "${sensorName} INACTIVE ${duration.setScale(0, BigDecimal.ROUND_HALF_UP)}"
    		state.evtData << [name: sensorName, label: sensor.label[sensorNum], event_type: sensorAttribute, value: "inactive", date: latestActivityState.dateCreated[sensorNum], duration: duration.setScale(0, BigDecimal.ROUND_HALF_UP)]
    	} else if (!state.activeState[sensorName] && (currentActivityState.value[sensorNum] == "active")) {
            state.activeState[sensorName] = true
            def duration = (now - state.changeTime[sensorName])/1000.0            
            state.changeTime[sensorName] = now
            //log.debug "${sensorName} ACTIVE ${duration.setScale(0, BigDecimal.ROUND_HALF_UP)}"
    		state.evtData << [name: sensorName, label: sensor.label[sensorNum], event_type: sensorAttribute, value: "active", date: latestActivityState.dateCreated[sensorNum], duration: duration.setScale(0, BigDecimal.ROUND_HALF_UP)]
    	}
    }
  }
}

def getLatestEventTimes() {
	def resp = [(switches.name[0]): switches.latestState("power").dateCreated[0], (motion.name[0]): motion.latestState("motion").dateCreated[0]] 
    for (def sensorNum = 0; sensorNum < acceleration.name.size(); sensorNum++) {
      resp << ["${acceleration.name[sensorNum]} acceleration": acceleration.latestState("acceleration").dateCreated[sensorNum], "${acceleration.name[sensorNum]} contact": contact.latestState("contact").dateCreated[sensorNum]]
    }
	if (water != null) {
    	resp << [(water.name[0]): water?.latestState("water").dateCreated[0]]
    }
	return resp
}

def postEventsHandler() {
	checkForActivity(motion,"motion")
    checkForActivity(acceleration,"acceleration")
    //checkForMotion()
	// Push if any processed event data
   	if (state.evtData.size() > 0) {
		def json = new groovy.json.JsonBuilder(["hub_id": location.hubs[0].id, "location": location.name, "processed": state.evtData, "raw": state.rawEvtData]).toString()
    	//log.debug("JSON: ${json}")
    	def params = [
    		uri: "https://pamf-connect-propane.linkages.org/smartthings.php",
    	    body: json
   		]
    	try {
        	httpPostJson(params)
    	} catch ( groovyx.net.http.HttpResponseException ex ) {
       		log.debug "Unexpected response error: ${ex.statusCode}"
    	}
        state.evtData = []   // clear processed event data
        state.rawEvtData = []  // clear raw event data
     }
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
	checkForActivity(acceleration,"acceleration")
    addRawEvent(evt)
}

def contactHandler(evt) {
	addEvent(evt)
    addRawEvent(evt)
}

def motionHandler(evt) {
	checkForActivity(motion,"motion")
    addRawEvent(evt)
}

def waterHandler(evt) {
	addEvent(evt)
    addRawEvent(evt)
}

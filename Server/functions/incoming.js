exports.handler = function(context, event, callback) {
  console.log('Incoming function called with event:', JSON.stringify(event));
  
  const twiml = new Twilio.twiml.VoiceResponse();

  // Check if this is a call to a client (your app)
  if (event.To && event.To.startsWith('client:')) {
    // This is a call to your app - connect it
    const clientName = event.To.replace('client:', '');
    console.log('Calling client:', clientName);
    twiml.say('Connecting you to ' + clientName);
    twiml.dial().client(clientName);
  } else {
    // This is a regular call - just play a message
    console.log('Regular call, not to client');
    twiml.say("Congratulations! You have received your first inbound call! Good bye.");
  }

  callback(null, twiml.toString());
};
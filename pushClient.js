var mqtt = require('mqtt');

exports.push = function(req,res){

  var target = req.body.target;
  var message = req.body.message;
  if (!target) {
    res.send(500);
    return;
  }
  var client = mqtt.createClient();
  client.publish(target, message);
  client.end();
  res.send(200);
}
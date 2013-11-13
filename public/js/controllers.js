'use strict';

/* Controllers */

var app = angular.module('app', []);

app.controller('AppCtrl',['$scope','$http','$timeout',function($scope,$http,$timeout){
  //Log
  $scope.logs = [];
  var socket = io.connect();
  socket.on('message', function (data) {
    $scope.$apply(function() {
      $scope.logs.push(data);
    });
  });
  //UserList
  function refreshUserList(){
    $http({method: 'GET', url: '/users'}).
    success(function(data, status, headers, config) {
        $scope.apps = data;
    });
    $timeout(refreshUserList, 60 * 1000);
  }
  refreshUserList();
  $scope.selectApp = function(appName){
    $scope.target = appName + "/*";
  };
  $scope.selectClient = function(appName,clientName){
    $scope.target = appName + "/" + clientName;
  };
  //Push
  $scope.push = function(){
    $http({
      method: 'POST', 
      url: '/push',
      data: {
        target : $scope.target,
        message : $scope.message
      }
    })
    .success(function(data, status, headers, config) {
        $scope.target = "";
        $scope.message = "";
        $scope.pushSuccess = true;
        $scope.alertMessage = "发送消息成功";
    })
    .error(function(data, status, headers, config) {
        $scope.pushSuccess = false;
        $scope.alertMessage = "发送消息失败";
    });
  };
}]);
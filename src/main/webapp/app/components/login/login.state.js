(function() {
    'use strict';

    angular
        .module('videoChatApp')
        .config(stateConfig);

    stateConfig.$inject = ['$stateProvider'];

    function stateConfig($stateProvider) {
        $stateProvider.state('login', {
        	parent: 'app',
            url: '/login',
            data: {
                authorities: []
            },
            views: {
                'content@': {
                	templateUrl: 'app/components/login/login.html',
                    controller: 'LoginController',
                    controllerAs: 'vm'
                }
            }
        });
    }
})();
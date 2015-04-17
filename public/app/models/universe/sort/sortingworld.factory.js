(function()
{
	'use strict';

	angular
		.module('PLMApp')
		.factory('SortingWorld', SortingWorld);

	SortingWorld.$inject = [ 'SetValOperation', 'SwapOperation','CopyOperation', 'CountOperation', 'GetValueOperation'
	];

	function SortingWorld(SetValOperation, SwapOperation, CopyOperation, CountOperation, GetValueOperation)
	{
		var SortingWorld = function(world)
		{
			this.type = world.type;
			this.width = world.width;
			this.height = world.height;
			this.operations = [];
			this.currentState = -1;
			this.readCount = world.readCount;
			this.writeCount = world.writeCount;
			
			this.values = [];
			for(var i=0;i<world.values.length;i++)
			{
				this.values.push(world.values[i]);
			}

			this.initValues = [];

			//contains each array of values after an operation
			this.memory = [];
			for(var i=0;i<world.values.length;i++)
			{
				this.initValues.push(world.values[i]);
			}

			this.memory.push(this.initValues);

		
			this.colors = [];
			for(var i=0;i<this.values.length;i++)
			{
				this.colors.push('#'+Math.floor(Math.random()*16777215).toString(16));
			}
			
		};

		SortingWorld.prototype.clone = function()
		{
			return new SortingWorld(this);
		}

		SortingWorld.prototype.addOperations = function (operations)
		{
			var step = [];
			for(var i=0; i<operations.length;i++)
			{
				var generatedOperation = this.generatedOperation(operations[i]);
				step.push(generatedOperation);
			}
			this.operations.push(step);
		};

		SortingWorld.prototype.generatedOperation = function (operation)
		{
			switch(operation.type) {
				case 'copyOperation':
					return new CopyOperation(operation);
				case 'setValOperation':
					return new SetValOperation(operation);
				case 'swapOperation':
					return new SwapOperation(operation);
				case 'countOperation':
					return new CountOperation(operation);
				case 'getValueOperation' :
					return new GetValueOperation(operation);
			}
		};

		SortingWorld.prototype.setState = function (state) {
			var i;
			var j;
			var step;
			if(state < this.operations.length && state >= -1) {
				if(this.currentState < state) {
					for(i=this.currentState+1; i<=state; i++) {
						step = this.operations[i];
						for(j=0; j<step.length; j++) {
							step[j].apply(this);
						}
					}
				}
				else {
					for(i=this.currentState; i>state; i--) {
						step = this.operations[i];
						for(j=0; j<step.length; j++) {
							step[j].reverse(this);
						}
					}
				}
				this.currentState = state;
			}
		};

		SortingWorld.prototype.getEntity = function(entityID)
		{
			return this.entities[entityID];
		}

		return SortingWorld;
	}
})();
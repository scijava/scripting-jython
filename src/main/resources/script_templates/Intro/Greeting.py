#@ String (label="Please enter your name", description="Name field") name
#@output String greeting

# A Jython script with parameters.
# It is the duty of the scripting framework to harvest
# the 'name' parameter from the user, and then display
# the 'greeting' output parameter, based on its type.

greeting = "Hello, " + name + "!"

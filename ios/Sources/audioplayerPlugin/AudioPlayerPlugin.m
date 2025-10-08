#import <Foundation/Foundation.h>
#import <Capacitor/Capacitor.h>

// Define the plugin using the CAP_PLUGIN Macro, and
// expose the plugin's methods using the CAP_PLUGIN_METHOD macro.
CAP_PLUGIN(AudioPlayerPlugin, "AudioPlayer",
           CAP_PLUGIN_METHOD(initialize, CAPPluginReturnPromise);
           CAP_PLUGIN_METHOD(prepare, CAPPluginReturnPromise);
           CAP_PLUGIN_METHOD(play, CAPPluginReturnPromise);
           CAP_PLUGIN_METHOD(pause, CAPPluginReturnPromise);
           CAP_PLUGIN_METHOD(stop, CAPPluginReturnPromise);
           CAP_PLUGIN_METHOD(previous, CAPPluginReturnPromise);
           CAP_PLUGIN_METHOD(next, CAPPluginReturnPromise);
           CAP_PLUGIN_METHOD(seekTo, CAPPluginReturnPromise);
           CAP_PLUGIN_METHOD(setQueue, CAPPluginReturnPromise);
           CAP_PLUGIN_METHOD(setRepeatMode, CAPPluginReturnPromise);
           CAP_PLUGIN_METHOD(setShuffleMode, CAPPluginReturnPromise);
           CAP_PLUGIN_METHOD(setVolume, CAPPluginReturnPromise);
           CAP_PLUGIN_METHOD(getPlayerState, CAPPluginReturnPromise);
)
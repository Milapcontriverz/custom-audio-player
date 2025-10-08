

require 'json'

package = JSON.parse(File.read(File.join(__dir__, 'package.json')))

Pod::Spec.new do |s|
  s.name = 'CapacitorAudioPlayerDemo'
  s.version = package['version']
  s.summary = package['description']
  s.license      = { :type => 'MIT', :file => 'LICENSE' }
  s.homepage     = 'https://github.com/contriverz/capacitor-audio-player'
  s.author       = { 'Contriverz' => 'info@contriverz.com' }
  s.source       = { :git => 'https://github.com/contriverz/capacitor-audio-player.git', :tag => s.version.to_s }
  s.source_files = 'ios/Sources/**/*.{swift,h,m,c,cc,mm,cpp}'
  s.ios.deployment_target = '14.0'
  s.dependency 'Capacitor'
  s.swift_version = '5.1'
end
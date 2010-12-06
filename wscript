#! /usr/bin/env python

VERSION='0.1'
APPNAME='engine'

srcdir = '.'
blddir = 'build'

import os, sys
import waf_ddf, waf_graphics, waf_dynamo, waf_gamesys, waf_physics, waf_render

if sys.platform == "win32":
    os.environ["PYTHONPATH"] = os.environ["PYTHONPATH"] + ";default/proto"
else:
    os.environ["PYTHONPATH"] = os.environ["PYTHONPATH"] + ":default/proto"

def init():
    pass

def set_options(opt):
    opt.sub_options('src')
    opt.tool_options('compiler_cc')
    opt.tool_options('compiler_cxx')
    opt.tool_options('waf_dynamo')

def configure(conf):
    conf.check_tool('java')
    conf.check_tool('compiler_cc')
    conf.check_tool('compiler_cxx')
    conf.check_tool('waf_dynamo')

    waf_graphics.configure(conf)
    waf_ddf.configure(conf)
    waf_physics.configure(conf)
    waf_render.configure(conf)
    waf_gamesys.configure(conf)

    conf.sub_config('src')

    dynamo_home = os.getenv('DYNAMO_HOME')
    if not dynamo_home:
        conf.fatal("DYNAMO_HOME not set")

    dynamo_ext = os.path.join(dynamo_home, "ext")

    if sys.platform == "darwin":
        platform = "darwin"
    elif sys.platform == "linux2":
        platform = "linux"
    elif sys.platform == "win32":
        platform = "win32"
    else:
        conf.fatal("Unable to determine platform")

    if platform == "darwin":
        conf.env.append_value('LINKFLAGS', ['-framework', 'Cocoa', '-framework', 'OpenGL', '-framework', 'OpenAL', '-framework', 'AGL', '-framework', 'IOKit', '-framework', 'Carbon'])
        # In order to get accurate result from the memory profiler this is necessary
        conf.env.append_value('LINKFLAGS', [ '-flat_namespace' ])

    if platform == "linux":
        conf.env.append_value('LINKFLAGS', ['-lglut', '-lXext', '-lX11', '-lXi', '-lGL', '-lGLU', '-lpthread'])

    if platform == "win32":
        conf.env.append_value('LINKFLAGS', ['opengl32.lib', 'user32.lib'])

    conf.env['LIB_GTEST'] = 'gtest'

    if sys.platform == "linux2":
        conf.env['LIB_PLATFORM_SOCKET'] = ''
    elif platform == "darwin":
        conf.env['LIB_PLATFORM_SOCKET'] = ''
    else:
        conf.env['LIB_PLATFORM_SOCKET'] = 'WS2_32'

    conf.env['STATICLIB_DDF'] = 'ddf'
    conf.env['STATICLIB_DLIB'] = 'dlib'
    conf.env['STATICLIB_RESOURCE'] = 'resource'
    conf.env['STATICLIB_GRAPHICS'] = 'graphics'
    conf.env['STATICLIB_GRAPHICS_NULL'] = 'graphics_null'
    conf.env['STATICLIB_RENDER'] = 'render'
    conf.env['STATICLIB_GAMESYS'] = 'gamesys'
    conf.env['STATICLIB_GAMEOBJECT'] = 'gameobject'
    conf.env['STATICLIB_LUA'] = 'lua'
    conf.env['STATICLIB_SCRIPT'] = 'script'
    conf.env['STATICLIB_DMGLFW'] = 'dmglfw'
    conf.env['STATICLIB_GUI'] = 'gui'
    conf.env['STATICLIB_PARTICLE'] = 'particle'
    conf.env['STATICLIB_SOUND'] = 'sound'
    conf.env['STATICLIB_SOUND_NULL'] = 'sound_null'
    conf.env['STATICLIB_ALUT'] = 'alut'
    conf.env['STATICLIB_HID'] = 'hid'
    conf.env['STATICLIB_HID_NULL'] = 'hid_null'
    conf.env['STATICLIB_INPUT'] = 'input'

    if sys.platform == "linux2":
        conf.env['LIB_OPENAL'] = 'openal'
    elif platform == "darwin":
        pass
    elif platform == "win32":
        conf.env['LIB_OPENAL'] = 'OpenAL32'
        conf.env.append_value('CPPPATH', os.path.join(dynamo_ext, "include", "AL"))

    conf.env.append_unique('CCDEFINES', 'DLIB_LOG_DOMAIN="ENGINE"')
    conf.env.append_unique('CXXDEFINES', 'DLIB_LOG_DOMAIN="ENGINE"')

def build(bld):
    bld.add_subdirs('content')
    bld.add_group()
    bld.add_subdirs('src')

    bld.install_files('${PREFIX}/bin', 'scripts/dmengine_memprofile.sh', chmod=0755)

import Options
def shutdown():
    # unit tests disabled on win32 for now
    if sys.platform != "win32":
        waf_dynamo.run_gtests(valgrind = True)

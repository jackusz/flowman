/*
 * Copyright 2018 Kaya Kupferschmidt
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.dimajix.flowman.tools.admin

import java.io.PrintStream

import scala.collection.JavaConversions._

import org.kohsuke.args4j.Argument
import org.kohsuke.args4j.CmdLineException
import org.kohsuke.args4j.CmdLineParser
import org.kohsuke.args4j.Option
import org.kohsuke.args4j.spi.SubCommand
import org.kohsuke.args4j.spi.SubCommandHandler
import org.kohsuke.args4j.spi.SubCommands

import com.dimajix.flowman.tools.admin.history.HistoryCommand


class Arguments(args:Array[String]) {
    @Option(name = "-h", aliases=Array("--help"), usage = "show help", help=true)
    var _help: Boolean = false
    @Option(name = "-P", aliases=Array("--profile"), usage = "activate profile with specified name", metaVar = "<profile>")
    var profiles: Array[String] = Array()
    @Option(name = "-D", aliases=Array("--env"), usage = "set environment variables which can be accessed inside config", metaVar = "<key=value>")
    var environment: Array[String] = Array()
    @Option(name = "--conf", usage = "set a Flowman or Spark config", metaVar = "<confname>=<value>")
    var config: Array[String] = Array()
    @Option(name = "--info", usage = "dump configuration information")
    var info: Boolean = false

    @Argument(required=false,index=0,metaVar="group",usage="the object to work with",handler=classOf[SubCommandHandler])
    @SubCommands(Array(
        new SubCommand(name="history",impl=classOf[HistoryCommand])
    ))
    var command:Command = _

    /**
      * Returns true if a help message is requested
      * @return
      */
    def help : Boolean = _help || command == null || command.help

    /**
      * Prints a context-aware help message
      */
    def printHelp(out:PrintStream = System.err) : Unit = {
        if (command != null) {
            command.printHelp(out)
        }
        else {
            new CmdLineParser(this).printUsage(out)
            out.println
        }
    }

    parseArgs(args)

    private def parseArgs(args: Array[String]) {
        val parser: CmdLineParser = new CmdLineParser(this)
        try {
            parser.parseArgument(args.toList)
        }
        catch {
            case e: CmdLineException => {
                e.getParser.printUsage(System.err)
                System.err.println
                throw e
            }
        }
    }
}

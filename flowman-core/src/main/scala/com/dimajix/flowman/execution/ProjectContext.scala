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

package com.dimajix.flowman.execution

import scala.collection.mutable

import org.slf4j.LoggerFactory

import com.dimajix.flowman.spec.JobIdentifier
import com.dimajix.flowman.spec.ConnectionIdentifier
import com.dimajix.flowman.spec.MappingIdentifier
import com.dimajix.flowman.spec.Namespace
import com.dimajix.flowman.spec.Profile
import com.dimajix.flowman.spec.Project
import com.dimajix.flowman.spec.RelationIdentifier
import com.dimajix.flowman.spec.TargetIdentifier
import com.dimajix.flowman.spec.connection.Connection
import com.dimajix.flowman.spec.connection.ConnectionSpec
import com.dimajix.flowman.spec.flow.Mapping
import com.dimajix.flowman.spec.job.Job
import com.dimajix.flowman.spec.model.Relation
import com.dimajix.flowman.spec.target.Target
import com.dimajix.flowman.templating.FileWrapper


object ProjectContext {
    class Builder private[ProjectContext](parent:Context, project:Project) extends AbstractContext.Builder[Builder,ProjectContext](parent, SettingLevel.PROJECT_SETTING) {
        require(parent != null)
        require(project != null)

        override protected val logger = LoggerFactory.getLogger(classOf[ProjectContext])

        override def withProfile(profile:Profile) : Builder = {
            withProfile(profile, SettingLevel.PROJECT_PROFILE)
            this
        }

        override protected def createContext(env:Map[String,(Any, Int)], config:Map[String,(String, Int)], connections:Map[String, ConnectionSpec]) : ProjectContext = {
            case object ProjectWrapper {
                def getBasedir() : FileWrapper = FileWrapper(project.basedir)
                def getFilename() : FileWrapper = FileWrapper(project.filename)
                def getName() : String = project.name
                def getVersion() : String = project.version

                override def toString: String = project.name
            }

            val fullEnv = env + ("project" -> ((ProjectWrapper, SettingLevel.SCOPE_OVERRIDE.level)))
            new ProjectContext(parent, project, fullEnv, config, connections)
        }
    }

    def builder(parent:Context, project:Project) = new Builder(parent, project)
}


/**
  * Execution context for a specific Flowman project. This will interpolate all resources within the project
  * or (if the resource is fully qualified) walks up into the parent context.
  * @param parent
  * @param project
  */
class ProjectContext private[execution](
   parent:Context,
   override val project:Project,
   fullEnv:Map[String,(Any, Int)],
   fullConfig:Map[String,(String, Int)],
   nonProjectConnections:Map[String, ConnectionSpec]
) extends AbstractContext(parent, fullEnv, fullConfig) {
    private val mappings = mutable.Map[String,Mapping]()
    private val relations = mutable.Map[String,Relation]()
    private val targets = mutable.Map[String,Target]()
    private val connections = mutable.Map[String,Connection]()
    private val bundles = mutable.Map[String,Job]()

    /**
      * Returns the namespace associated with this context. Can be null
      * @return
      */
    override def namespace : Namespace = parent.namespace

    /**
      * Returns the root context in a hierarchy of connected contexts
      * @return
      */
    override def root : RootContext = parent.root

    /**
      * Returns a specific named Transform. The Transform can either be inside this Contexts project or in a different
      * project within the same namespace
      *
      * @param identifier
      * @return
      */
    override def getMapping(identifier: MappingIdentifier): Mapping = {
        require(identifier != null && identifier.nonEmpty)

        if (identifier.project.forall(_ == project.name)) {
            mappings.getOrElseUpdate(identifier.name,
                project.mappings
                    .getOrElse(identifier.name,
                        throw new NoSuchMappingException(identifier)
                    )
                    .instantiate(this)
            )
        }
        else {
            parent.getMapping(identifier)
        }
    }

    /**
      * Returns a specific named RelationType. The RelationType can either be inside this Contexts project or in a different
      * project within the same namespace
      *
      * @param identifier
      * @return
      */
    override def getRelation(identifier: RelationIdentifier): Relation = {
        require(identifier != null && identifier.nonEmpty)

        if (identifier.project.forall(_ == project.name)) {
            relations.getOrElseUpdate(identifier.name,
                project.relations
                    .getOrElse(identifier.name,
                        throw new NoSuchRelationException(identifier)
                    )
                    .instantiate(this)
            )
        }
        else {
            parent.getRelation(identifier)
        }
    }

    /**
      * Returns a specific named TargetType. The TargetType can either be inside this Contexts project or in a different
      * project within the same namespace
      *
      * @param identifier
      * @return
      */
    override def getTarget(identifier: TargetIdentifier): Target = {
        require(identifier != null && identifier.nonEmpty)

        if (identifier.project.forall(_ == project.name)) {
            targets.getOrElseUpdate(identifier.name,
                project.targets
                    .getOrElse(identifier.name,
                        throw new NoSuchTargetException(identifier)
                    )
                    .instantiate(this)
            )
        }
        else {
            parent.getTarget(identifier)
        }
    }

    /**
      * Try to retrieve the specified database. Performs lookups in parent context if required
      *
      * @param identifier
      * @return
      */
    override def getConnection(identifier:ConnectionIdentifier) : Connection = {
        require(identifier != null && identifier.nonEmpty)

        if (identifier.project.forall(_ == project.name)) {
            connections.getOrElseUpdate(identifier.name,
                if (identifier.project.nonEmpty) {
                    // Explicit project identifier specified, only look in project connections
                    project.connections
                        .getOrElse(identifier.name,
                            throw new NoSuchConnectionException(identifier)
                        )
                        .instantiate(this)
                }
                else {
                    // No project specifier given, first look into non-project connections, then try project connections
                    nonProjectConnections.getOrElse(identifier.name,
                        project.connections
                            .getOrElse(identifier.name,
                                throw new NoSuchConnectionException(identifier)
                            )
                    )
                    .instantiate(this)
                }
            )
        }
        else {
            parent.getConnection(identifier)
        }
    }

    /**
      * Returns a specific named Job. The JobType can either be inside this Contexts project or in a different
      * project within the same namespace
      *
      * @param identifier
      * @return
      */
    override def getJob(identifier: JobIdentifier): Job = {
        require(identifier != null && identifier.nonEmpty)

        if (identifier.project.forall(_ == project.name)) {
            bundles.getOrElseUpdate(identifier.name,
                project.jobs
                    .getOrElse(identifier.name,
                        throw new NoSuchBundleException(identifier)
                    )
                    .instantiate(this)
            )
        }
        else {
            parent.getJob(identifier)
        }
    }
}

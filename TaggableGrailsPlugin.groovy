/* Copyright 2006-2007 Graeme Rocher
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
import org.grails.taggable.*
import grails.util.*

/**
 * A plugin that adds a generic mechanism for tagging data 

 * @author Graeme Rocher
 */
class TaggableGrailsPlugin {
    def groupId = 'org.icescrum'
    def version = "1.1.9"
    def grailsVersion = "2.3 > *"
    def license = 'APACHE'
    def pluginExcludes = [
            "grails-app/views/error.gsp",
            "grails-app/domain/org/grails/taggable/Test*.groovy"
    ]

    def observe = ['hibernate']
    def developers = [
        [ name: "Graeme Rocher", email: "graeme.rocher@springsource.com" ]
    ]
    def title = "Taggable Plugin"
    def description = '''\
A plugin that adds a generic mechanism for tagging data.
'''

    // URL to the plugin's documentation
    def documentation = "http://grails.org/plugin/taggable"
    def issueManagement = [system: "JIRA", url: "http://jira.grails.org/browse/GPTAGGABLE"]
    def scm = [url: "https://github.com/gpc/grails-taggable"]
	def organization = [ name: "Grails Plugin Collective", url: "http://github.com/gpc" ]

    def doWithDynamicMethods = {
        def tagService = applicationContext.taggableService
        tagService.refreshDomainClasses()

        for(domainClass in application.domainClasses) {
            if(Taggable.class.isAssignableFrom(domainClass.clazz)) {
                domainClass.clazz.metaClass {
                    addTag { String name ->
                        if(delegate.id == null) throw new TagException("You need to save the domain instance before tagging it")
                        if (!Tag.preserveCase) {
                            name = name.toLowerCase()
                        }
                        def matchingTags = Tag.findAllByName(name, [cache:true]) // Can return several results for DBMS with case insensitive search (e.g. MySQL)
                        def tag = matchingTags.find { it.name == name } ?: new Tag(name:name).save() // We want a new tag "Foo" even if we found "foo"
                        if(!tag) throw new TagException("Value [$name] is not a valid tag")
                        
                        def criteria = TagLink.createCriteria()
                        def instance = delegate
                        def link = criteria.get {
                            eq 'tag', tag
                            eq 'tagRef', instance.id
                            eq 'type', GrailsNameUtils.getPropertyName(instance.class)
                            cache true
                        }
                        
                        if(!link) {
                            link = new TagLink(tag:tag, tagRef:delegate.id, type:GrailsNameUtils.getPropertyName(delegate.class)).save()
                        }
                        return delegate // for method chaining
                    }
                    
                    addTags { names ->
                        names.each { delegate.addTag it }
                    }
                    
                    getTags {->
                        delegate.id ? getTagLinks(tagService, delegate).tag.name : []
                    }                    
                    parseTags { String tags, String delimiter = "," ->
                        tags.split(delimiter).each { 
                            def tag = it.trim()
                            if(tag) addTag(tag) 
                        }
                        return delegate
                    }
                    removeTag { String name ->
                        if(delegate.id == null) throw new TagException("You need to save the domain instance before tagging it")
                        
                        if (!Tag.preserveCase) {
                            name = name.toLowerCase()
                        }
                        
                        def criteria = TagLink.createCriteria()
                        def instance = delegate
                        def link = criteria.get {
                            tag {
                                if (!Tag.preserveCase) {
                                    eq 'name', name
                                } else {
                                    ilike 'name', name
                                }
                            }
                            eq 'tagRef', instance.id
                            eq 'type', GrailsNameUtils.getPropertyName(instance.class)
                            cache true
                        }                        
                        link?.delete()
                        return delegate
                    }
                    setTags { List tags ->
                        // remove invalid tags
                        tags =  tags?.findAll { it }

                        if (tags) {
                            // remove old tags that not appear in the new tags
                            getTagLinks(tagService, delegate)*.each { TagLink tagLink ->
                                if (tags.contains(tagLink.tag.name)) {
                                    tags.remove(tagLink.tag.name)
                                } else {
                                    tagLink.delete()
                                }
                            }

                            // add the rest
                            addTags(tags)
                        } else {
                            getTagLinks(tagService, delegate)*.delete()
                        }
                    }
                    
                    'static' {
                        
                        getAllTags {->
                            def clazz = delegate
                            TagLink.withCriteria {
                                projections { tag { distinct "name" } }
                                'in'('type', tagService.getTaggablePropertyName(clazz.name))
                                cache true
                            }
                        }
                        getTotalTags = {->
                            def clazz = delegate
                            TagLink.createCriteria().get {
                                projections { tag { countDistinct "name" } }
                                'in'('type', tagService.getTaggablePropertyName(clazz.name))
                                cache true
                            }                            
                        }
                        countByTag { String tag ->
                            def identifiers = TaggableGrailsPlugin.getTagReferences(tagService, tag, delegate.name)
                            if(identifiers) {
                                def criteria = delegate.createCriteria()
                                criteria.get {
                                    projections {
                                        rowCount()
                                    }
                                    inList 'id', identifiers
                                    cache true
                                }
                            }
                            else {
                                return 0                                
                            }
                        }
                        
                        findAllByTag { String name->
                            def identifiers = TaggableGrailsPlugin.getTagReferences(tagService, name, delegate.name)
                            if(identifiers) {
                                delegate.findAllByIdInList(identifiers, [cache:true])
                            }
                            else {
                                return Collections.EMPTY_LIST                                
                            }
                        }
                        findAllByTag { String name, Map args->
                            def identifiers = TaggableGrailsPlugin.getTagReferences(tagService, name, delegate.name)
                            if(identifiers) {
                                args.cache=true
                                delegate.findAllByIdInList(identifiers, args)
                            }
                            else {
                                return Collections.EMPTY_LIST                                
                            }
                        }
                        findAllByTagWithCriteria { String name, Closure crit ->
                            def clazz = delegate
                            def identifiers = TaggableGrailsPlugin.getTagReferences(tagService, name, clazz.name)
                            if(identifiers) {
                                args.cache=true
                                return clazz.withCriteria {
                                    'in'('id', identifiers)

                                    crit.delegate = delegate
                                    crit.call()
                                }
                            }
                            else {
                                return Collections.EMPTY_LIST                                                           
                            }
                        }
                        findAllByTagsWithCriteria { List<String> names, Closure crit ->
                            def clazz = delegate
                            def identifiers = []
                            names.each { name ->
                                identifiers.addAll(TaggableGrailsPlugin.getTagReferences(tagService, name, clazz.name))
                            }
                            if(identifiers) {
                                args.cache=true
                                return clazz.withCriteria {
                                    'in'('id', identifiers)

                                    crit.delegate = delegate
                                    crit.call()
                                }
                            }
                            else {
                                return Collections.EMPTY_LIST
                            }
                        }
                        findAllTagsWithCriteria { Map params, Closure criteria ->
                            def clazz = delegate
                            TagLink.withCriteria {
                                projections { tag { distinct "name" } }
                                'in'('type', tagService.getTaggablePropertyName(clazz.name))
                                cache true
                                tag(criteria)

                                if (params.offset != null) {
                                    firstResult(params.offset.toInteger())
                                }
                                if (params.max != null) {
                                    maxResults(params.max.toInteger())
                                }
                                tag {
                                    order('name', 'asc')
                                }
                            }
                        }

                    }
                }
            }
        }
    }

    private getTagLinks(tagService, obj) {
        TagLink.findAllByTagRefAndTypeInList(obj.id, tagService.getTaggablePropertyName(obj.class.name), [cache:true])
    }
    
    def onChange = { event ->
        applicationContext.taggableService.refreshDomainClasses()
    }

    static getTagReferences(tagService, String tagName, String className) {
        if(tagName) {
            TagLink.withCriteria {
                projections {
                    property 'tagRef'
                }
                tag {
                    eq('name', tagName, [ignoreCase: true])
                }                
                'in'('type', tagService.getTaggablePropertyName(className))
                cache true
            }
            
        }    
        else {
            return Collections.EMPTY_LIST
        }    
    }

}

package com.avast.gradle.dockercompose

import com.avast.gradle.dockercompose.tasks.ComposeDown
import com.avast.gradle.dockercompose.tasks.ComposePull
import com.avast.gradle.dockercompose.tasks.ComposeUp
import org.gradle.api.Task
import org.gradle.api.tasks.testing.Test
import org.gradle.testfixtures.ProjectBuilder
import spock.lang.IgnoreIf
import spock.lang.Specification

import static org.gradle.util.VersionNumber.parse

class DockerComposePluginTest extends Specification {
    def "add tasks and extension to the project"() {
        def project = ProjectBuilder.builder().build()
        when:
            project.plugins.apply 'docker-compose'
        then:
            project.tasks.composeUp instanceof ComposeUp
            project.tasks.composeDown instanceof ComposeDown
            project.tasks.composePull instanceof ComposePull
            project.extensions.findByName('dockerCompose') instanceof ComposeExtension
    }

    def "add tasks of nested settings"() {
        def project = ProjectBuilder.builder().build()
        when:
        project.plugins.apply 'docker-compose'
        project.dockerCompose {
                nested {
                    useComposeFiles = ['test.yml']
                }
            }
        then:
        project.tasks.nestedComposeUp instanceof ComposeUp
        project.tasks.nestedComposeDown instanceof ComposeDown
        project.tasks.nestedComposePull instanceof ComposePull
        ComposeUp up = project.tasks.nestedComposeUp
        up.settings.useComposeFiles == ['test.yml']
    }

    def "dockerCompose.isRequiredBy() adds dependencies"() {
        def project = ProjectBuilder.builder().build()
        project.plugins.apply 'docker-compose'
        Task task = project.tasks.create('integrationTest')
        when:
            project.dockerCompose.isRequiredBy(task)
        then:
            task.dependsOn.contains(project.tasks.composeUp)
            task.getFinalizedBy().getDependencies(task).any { it == project.tasks.composeDown }
    }

    def "dockerCompose.isRequiredBy() adds dependencies for nested settings"() {
        def project = ProjectBuilder.builder().build()
        project.plugins.apply 'docker-compose'
        Task task = project.tasks.create('integrationTest')
        when:
        project.dockerCompose {
            nested {
                useComposeFiles = ['test.yml']
                isRequiredBy(task)
            }
        }
        then:
        task.dependsOn.contains(project.tasks.nestedComposeUp)
        task.getFinalizedBy().getDependencies(task).any { it == project.tasks.nestedComposeDown }
    }

    def "isRequiredBy ensures right order of tasks"() {
        def project = ProjectBuilder.builder().build()
        project.plugins.apply 'docker-compose'
        project.plugins.apply 'java'
        when:
            project.dockerCompose.isRequiredBy(project.tasks.test)
        then:
            project.tasks.composeUp.shouldRunAfter.values.any { it == project.tasks.testClasses }
            noExceptionThrown()
    }

    def "allows usage from integration test"() {
        def f = Fixture.withNginx()
        f.project.tasks.create('integrationTest').doLast {
            ContainerInfo webInfo = f.project.dockerCompose.servicesInfos.web.firstContainer
            assert "http://${webInfo.host}:${webInfo.tcpPorts[80]}".toURL().text.contains('nginx')
            assert webInfo.ports == webInfo.tcpPorts
            assert !webInfo.containerHostname.isEmpty()
            assert webInfo.inspection.size() > 0
        }
        when:
            f.project.tasks.composeUp.up()
            f.project.tasks.integrationTest.execute()
        then:
            noExceptionThrown()
        cleanup:
            f.project.tasks.composeDown.down()
            f.close()
    }

    def "allows pull"() {
        def f = Fixture.withNginx()
        when:
            f.project.tasks.composePull.pull()
        then:
            noExceptionThrown()
        cleanup:
            f.close()
    }

    def "exposes environment variables and system properties"() {
        def f = Fixture.custom(composeFileContent)
        f.project.plugins.apply 'java'
        f.project.tasks.composeUp.up()
        Test test = f.project.tasks.test as Test
        when:
            f.project.dockerCompose.exposeAsEnvironment(test)
            f.project.dockerCompose.exposeAsSystemProperties(test)
        then:
            test.environment.containsKey('WEB_HOST')
            test.environment.containsKey('WEB_CONTAINER_HOSTNAME')
            test.environment.containsKey('WEB_TCP_80')
            test.systemProperties.containsKey('web.host')
            test.systemProperties.containsKey('web.containerHostname')
            test.systemProperties.containsKey('web.tcp.80')
        cleanup:
            f.project.tasks.composeDown.down()
            f.close()
        where:
            // test it for both compose file version 1 and 2
            composeFileContent << ['''
            web:
                image: nginx
                ports:
                  - 80
        ''', '''
            version: '2'
            services:
                web:
                    image: nginx
                    ports:
                      - 80
        ''']
    }

    @IgnoreIf({ System.properties['os.name'].toString().toLowerCase().startsWith('windows') || System.properties['os.name'].toString().toLowerCase().startsWith('macos') })
    def "expose localhost as a host for container with HOST networking"() {
        def f = Fixture.custom('''
            version: '2'
            services:
                web:
                    image: nginx
                    network_mode: host
                    ports:
                      - 80
        ''')
        f.project.plugins.apply 'java'
        f.extension.projectName = 'test'
        f.project.tasks.composeUp.up()
        Test test = f.project.tasks.test as Test
        when:
            f.project.dockerCompose.exposeAsEnvironment(test)
            f.project.dockerCompose.exposeAsSystemProperties(test)
        then:
            test.environment.get('WEB_HOST') == 'localhost'
            test.systemProperties.get('web.host') == 'localhost'
        cleanup:
            f.project.tasks.composeDown.down()
            f.close()
    }

    def "docker-compose substitutes environment variables"() {
        def f = Fixture.custom('''
            web:
                image: nginx
                ports:
                  - $MY_WEB_PORT
        ''')
        f.project.tasks.create('integrationTest').doLast {
            ContainerInfo webInfo = f.project.dockerCompose.servicesInfos.web.firstContainer
            assert webInfo.ports.containsKey(80)
        }
        when:
            f.extension.useComposeFiles = ['docker-compose.yml']
            f.extension.environment.put 'MY_WEB_PORT', 80
            f.extension.waitForTcpPorts = false  // checked in assert
            f.project.tasks.composeUp.up()
            f.project.tasks.integrationTest.execute()
        then:
            noExceptionThrown()
        cleanup:
            f.project.tasks.composeDown.down()
            f.close()
    }

    @IgnoreIf({ System.getenv('DOCKER_COMPOSE_VERSION') == null || parse(System.getenv('DOCKER_COMPOSE_VERSION')) >= parse('1.13.0') })
    def "exception is thrown for scale option if unsupported docker-compose is used"() {
        def f = Fixture.withNginx()
        f.extension.scale = ['web': 2]
        when:
            f.project.tasks.composeUp.up()
        then:
            thrown(UnsupportedOperationException)
        cleanup:
            f.project.tasks.composeDown.down()
            f.close()
    }

    @IgnoreIf({ parse(System.getenv('DOCKER_COMPOSE_VERSION')) < parse('1.13.0') })
    def "docker-compose scale option launches multiple instances of service"() {
        def f = Fixture.withNginx()
        f.extension.scale = ['web': 2]
        f.project.tasks.create('integrationTest').doLast {
            def webInfos = project.dockerCompose.servicesInfos.web.containerInfos
            assert webInfos.size() == 2
            assert webInfos.containsKey('web_1')
            assert webInfos.containsKey('web_2')
        }
        when:
            f.project.tasks.composeUp.up()
            f.project.tasks.integrationTest.execute()
        then:
            noExceptionThrown()
        cleanup:
            f.project.tasks.composeDown.down()
            f.close()
    }

    @IgnoreIf({ parse(System.getenv('DOCKER_COMPOSE_VERSION')) < parse('1.13.0') })
    def "environment variables and system properties exposed for all scaled containers"() {
        def f = Fixture.withNginx()
        f.project.plugins.apply 'java'
        f.extension.scale = ['web': 2]
        f.project.tasks.composeUp.up()
        Test test = f.project.tasks.test as Test
        when:
            f.project.dockerCompose.exposeAsEnvironment(test)
            f.project.dockerCompose.exposeAsSystemProperties(test)
        then:
            [1, 2].each { containerInstance ->
                assert test.environment.containsKey("WEB_${containerInstance}_HOST".toString())
                assert test.environment.containsKey("WEB_${containerInstance}_CONTAINER_HOSTNAME".toString())
                assert test.environment.containsKey("WEB_${containerInstance}_TCP_80".toString())
                assert test.systemProperties.containsKey("web_${containerInstance}.host".toString())
                assert test.systemProperties.containsKey("web_${containerInstance}.containerHostname".toString())
                assert test.systemProperties.containsKey("web_${containerInstance}.tcp.80".toString())
            }
        cleanup:
            f.project.tasks.composeDown.down()
            f.close()
    }

    def "exposes environment variables and system properties for container with custom name"() {
        def f = Fixture.custom(composeFileContent)
        f.project.plugins.apply 'java'
        f.project.tasks.composeUp.up()
        Test test = f.project.tasks.test as Test
        when:
        f.project.dockerCompose.exposeAsEnvironment(test)
        f.project.dockerCompose.exposeAsSystemProperties(test)
        then:
        test.environment.containsKey('CUSTOM_CONTAINER_NAME_HOST')
        test.environment.containsKey('CUSTOM_CONTAINER_NAME_CONTAINER_HOSTNAME')
        test.environment.containsKey('CUSTOM_CONTAINER_NAME_TCP_80')
        test.systemProperties.containsKey('custom_container_name.host')
        test.systemProperties.containsKey('custom_container_name.containerHostname')
        test.systemProperties.containsKey('custom_container_name.tcp.80')
        cleanup:
        f.project.tasks.composeDown.down()
        f.close()
        where:
        // test it for both compose file version 1 and 2
        composeFileContent << ['''
            web:
                container_name: custom_container_name
                image: nginx
                ports:
                  - 80
        ''', '''
            version: '2'
            services:
                web:
                    container_name: custom_container_name
                    image: nginx
                    ports:
                      - 80
        ''']
    }
}

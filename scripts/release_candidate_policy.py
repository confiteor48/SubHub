"""Private candidate flags must never reach a public or automatic publication."""
import argparse


def boolean(value):
    if value not in ('true', 'false'):
        raise ValueError('Candidate flags must be literal true or false')
    return value == 'true'


def validate(event_name, publish_release, immediate_quality_experiment, replacement_lab_candidate):
    flags = (immediate_quality_experiment, replacement_lab_candidate)
    if type(publish_release) is not bool or any(type(flag) is not bool for flag in flags):
        raise ValueError('Candidate policy requires Boolean flags')
    if any(flags) and (event_name != 'workflow_dispatch' or publish_release):
        raise ValueError('Experimental flags require a manual private signed build')


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--event-name', required=True)
    parser.add_argument('--publish-release', required=True)
    parser.add_argument('--immediate-quality-experiment', required=True)
    parser.add_argument('--replacement-lab-candidate', required=True)
    args = parser.parse_args()
    try:
        validate(args.event_name, boolean(args.publish_release),
                 boolean(args.immediate_quality_experiment), boolean(args.replacement_lab_candidate))
    except ValueError as error:
        parser.error(str(error))
    print('Candidate publication policy verified.')


if __name__ == '__main__':
    main()
